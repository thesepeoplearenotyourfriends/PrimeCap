package com.openrecorder.app;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Receives shell-daemon H.264 output and writes it directly to RecordingMuxer. */
final class PrimeCapVideoRecorder {
    interface Listener {
        void onLimitReached();
        void onFailure(Exception error);
    }

    private static final String TAG = "PrimeCapVideo";
    private static final String DAEMON_SOCKET = "primecap_video_daemon";
    private static final int MAGIC = 0x50434150;
    private static final int PROTOCOL_VERSION = 3;
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final long START_TIMEOUT_MS = 12_000L;
    private static final long STOP_TIMEOUT_MS = 8_000L;
    private static final long SOURCE_CLOCK_TOLERANCE_NANOS = 30_000_000_000L;

    private final int maxSize;
    private final int bitRate;
    private final int frameRate;
    private final int recordingOrientation;
    private final long maximumFileSize;
    private final Listener listener;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicBoolean limitNotified = new AtomicBoolean();
    private final CountDownLatch formatReady = new CountDownLatch(1);
    private final CountDownLatch receiverFinished = new CountDownLatch(1);
    private final CountDownLatch timelineReady = new CountDownLatch(1);

    private RecordingMuxer.Track outputTrack;
    private RecordingTimeline timeline;
    private LocalSocket socket;
    private Thread receiverThread;
    private volatile DataOutputStream daemonControl;
    private volatile boolean stopRequested;
    private volatile boolean released;
    private volatile int daemonPid = -1;
    private volatile int daemonUid = -1;
    private boolean formatReceived;
    private boolean started;
    private boolean sourceClockResolved;
    private long sourceToMonotonicOffsetNanos;
    private long lastWrittenPresentationTimeUs = -1L;
    private long encodedBytesWritten;

    PrimeCapVideoRecorder(int width, int height, int bitRate,
            int frameRate, int recordingOrientation, long maximumFileSize, Listener listener) {
        this.maxSize = Math.max(width, height);
        this.bitRate = bitRate;
        this.frameRate = frameRate;
        this.recordingOrientation = RecordingOptions.normalizeOrientation(recordingOrientation);
        this.maximumFileSize = maximumFileSize;
        this.listener = listener;
    }

    synchronized void prepare() throws IOException {
        // The video daemon is installed and started independently from the APK.
    }

    synchronized void setOutputTrack(RecordingMuxer.Track track) {
        if (started || outputTrack != null || track == null) {
            throw new IllegalStateException("Video output track cannot be changed");
        }
        outputTrack = track;
    }

    synchronized void start() throws IOException {
        if (started || outputTrack == null) {
            throw new IllegalStateException("Video recorder is not configured");
        }
        started = true;
        receiverThread = new Thread(this::receive, "PrimeCapVideoReceiver");
        receiverThread.start();

        boolean ready;
        try {
            ready = formatReady.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            closeSockets();
            throw new IOException("Interrupted while starting PrimeCap video", error);
        }
        if (!ready) {
            closeSockets();
            throw new VideoDaemonUnavailableException(
                    "Video daemon unavailable: timed out waiting for video format");
        }
        Exception startupFailure = failure.get();
        if (startupFailure != null) {
            closeSockets();
            if (startupFailure instanceof VideoDaemonUnavailableException) {
                throw (VideoDaemonUnavailableException) startupFailure;
            }
            throw new IOException("Unable to start video daemon capture", startupFailure);
        }
    }

    synchronized void arm(RecordingTimeline recordingTimeline) {
        if (!started || timeline != null || recordingTimeline == null || formatReady.getCount() != 0) {
            throw new IllegalStateException("Video recorder is not ready to arm");
        }
        timeline = recordingTimeline;
        timelineReady.countDown();
    }

    void pause() {
        // The daemon deliberately keeps encoding; RecordingTimeline drops paused samples.
    }

    void resume() {
        // No daemon state changes are needed when the pause-free timeline resumes.
    }

    synchronized void requestStop() {
        if (!started || stopRequested) {
            return;
        }
        stopRequested = true;
        DataOutputStream control = daemonControl;
        if (control != null) {
            try {
                control.writeByte(COMMAND_STOP);
                control.flush();
            } catch (IOException error) {
                Log.w(TAG, "Unable to send daemon stop request", error);
            }
        }
    }

    void awaitStopped() throws IOException {
        if (!started) {
            return;
        }
        boolean completed;
        try {
            completed = receiverFinished.await(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while stopping PrimeCap video", error);
        }
        if (!completed) {
            closeSockets();
            throw new IOException("Timed out while stopping the PrimeCap video daemon");
        }
        Exception receiverFailure = failure.get();
        if (receiverFailure != null) {
            throw new IOException("PrimeCap video capture failed", receiverFailure);
        }
    }

    synchronized void release() {
        released = true;
        timelineReady.countDown();
        requestStop();
        closeSockets();
    }

    private void receive() {
        boolean cleanEnd = false;
        try {
            socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(
                        DAEMON_SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            } catch (IOException error) {
                throw new VideoDaemonUnavailableException(
                        "Video daemon unavailable; start primecap-video-daemon from adb shell",
                        error);
            }
            daemonControl = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            daemonControl.writeInt(MAGIC);
            daemonControl.writeInt(PROTOCOL_VERSION);
            daemonControl.writeByte(COMMAND_START);
            daemonControl.writeInt(maxSize);
            daemonControl.writeInt(bitRate);
            daemonControl.writeFloat((float) frameRate);
            daemonControl.writeInt(recordingOrientation);
            daemonControl.flush();
            if (input.readInt() != MAGIC || input.readInt() != PROTOCOL_VERSION) {
                throw new IOException("Unsupported PrimeCap video daemon protocol");
            }
            daemonPid = input.readInt();
            daemonUid = input.readInt();
            Log.i(TAG, "Connected to video daemon pid=" + daemonPid + " uid=" + daemonUid);
            if (stopRequested) {
                daemonControl.writeByte(COMMAND_STOP);
                daemonControl.flush();
            }
            while (true) {
                int type = input.readUnsignedByte();
                int length = input.readInt();
                if (length < 0 || length > MAX_PAYLOAD) {
                    throw new IOException("Invalid PrimeCap packet length: " + length);
                }
                if (type == TYPE_FORMAT) {
                    readFormat(input, length);
                } else if (type == TYPE_SAMPLE) {
                    readSample(input, length);
                } else if (type == TYPE_END) {
                    requireLength(type, length, 0);
                    cleanEnd = true;
                    break;
                } else if (type == TYPE_ERROR) {
                    byte[] message = readBytes(input, length);
                    throw new IOException("PrimeCap video daemon: " + new String(message, "UTF-8"));
                } else {
                    throw new IOException("Unknown PrimeCap packet type: " + type);
                }
            }
        } catch (Exception error) {
            if (!released) {
                recordFailure(error);
            }
        } finally {
            if (!cleanEnd && !released && failure.get() == null) {
                recordFailure(new IOException("PrimeCap video daemon disconnected unexpectedly"));
            }
            formatReady.countDown();
            outputTrack.finish();
            closeSockets();
            receiverFinished.countDown();
        }
    }

    private void readFormat(DataInputStream input, int length) throws IOException {
        if (formatReceived) {
            throw new IOException("PrimeCap video format changed more than once");
        }
        if (length < 16) {
            throw new IOException("Invalid PrimeCap FORMAT packet");
        }
        int width = input.readInt();
        int height = input.readInt();
        int csd0Length = input.readInt();
        if (width <= 0 || height <= 0 || csd0Length <= 0 || csd0Length > length - 16) {
            throw new IOException("Invalid PrimeCap H.264 format metadata");
        }
        byte[] csd0 = readBytes(input, csd0Length);
        int csd1Length = input.readInt();
        if (csd1Length < 0 || csd0Length + csd1Length != length - 16) {
            throw new IOException("Invalid PrimeCap H.264 codec configuration");
        }
        byte[] csd1 = readBytes(input, csd1Length);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        if (csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        }
        outputTrack.setFormat(format);
        formatReceived = true;
        Log.i(TAG, "Daemon H.264 format: " + width + "x" + height);
        formatReady.countDown();
    }

    private void readSample(DataInputStream input, int length) throws IOException {
        if (length < 16) {
            throw new IOException("Invalid PrimeCap SAMPLE packet");
        }
        long sourcePtsUs = input.readLong();
        int flags = input.readInt();
        int sampleLength = input.readInt();
        if (sampleLength < 0 || sampleLength != length - 16) {
            throw new IOException("Invalid PrimeCap sample size");
        }
        byte[] sample = readBytes(input, sampleLength);
        try {
            timelineReady.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while arming the recording timeline", error);
        }
        if (released || timeline == null) {
            return;
        }
        long adjustedPtsUs = adjustPresentationTime(sourcePtsUs);
        if (adjustedPtsUs < 0) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.length, adjustedPtsUs,
                flags & (MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_PARTIAL_FRAME));
        outputTrack.writeSampleData(ByteBuffer.wrap(sample), info);
        encodedBytesWritten += sample.length;
        if (encodedBytesWritten >= maximumFileSize
                && limitNotified.compareAndSet(false, true)) {
            listener.onLimitReached();
        }
    }

    private long adjustPresentationTime(long sourcePresentationTimeUs) {
        long sourceNanos = sourcePresentationTimeUs * 1_000L;
        if (!sourceClockResolved) {
            long difference = sourceNanos - timeline.getStartedAtNanos();
            if (difference > SOURCE_CLOCK_TOLERANCE_NANOS
                    || difference < -SOURCE_CLOCK_TOLERANCE_NANOS) {
                sourceToMonotonicOffsetNanos = timeline.getStartedAtNanos() - sourceNanos;
                Log.w(TAG, "Daemon timestamps use a different origin; applying a clock offset");
            }
            sourceClockResolved = true;
        }
        long timestampNanos = sourceNanos + sourceToMonotonicOffsetNanos;
        if (!timeline.shouldInclude(timestampNanos)) {
            return -1L;
        }
        long adjusted = timeline.toPresentationTimeUs(timestampNanos);
        adjusted = VideoTimestampNormalizer.ensureFrameSpacing(
                adjusted, lastWrittenPresentationTimeUs, frameRate);
        lastWrittenPresentationTimeUs = adjusted;
        return adjusted;
    }

    private void recordFailure(Exception error) {
        if (failure.compareAndSet(null, error)) {
            formatReady.countDown();
            try {
                listener.onFailure(error);
            } catch (RuntimeException listenerError) {
                Log.w(TAG, "Video failure listener failed", listenerError);
            }
        }
    }

    private synchronized void closeSockets() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            socket = null;
        }
    }

    private static byte[] readBytes(DataInputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    private static void requireLength(int type, int actual, int expected) throws IOException {
        if (actual != expected) {
            throw new IOException("Invalid length for packet " + type);
        }
    }

}
