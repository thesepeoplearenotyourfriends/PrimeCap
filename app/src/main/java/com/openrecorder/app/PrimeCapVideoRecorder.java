package com.openrecorder.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Receives privileged scrcpy H.264 output and writes it directly to RecordingMuxer. */
final class PrimeCapVideoRecorder {
    interface Listener {
        void onLimitReached();
        void onFailure(Exception error);
    }

    private static final String TAG = "PrimeCapVideo";
    private static final String HELPER_ASSET = "primecap-server";
    private static final int MAGIC = 0x50434150;
    private static final int PROTOCOL_VERSION = 1;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final long START_TIMEOUT_MS = 12_000L;
    private static final long STOP_TIMEOUT_MS = 8_000L;
    private static final long SOURCE_CLOCK_TOLERANCE_NANOS = 30_000_000_000L;
    private static final long MAX_DURATION_US = 60L * 60L * 1_000_000L;

    private final Context context;
    private final int maxSize;
    private final int bitRate;
    private final int frameRate;
    private final long maximumFileSize;
    private final Listener listener;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicBoolean limitNotified = new AtomicBoolean();
    private final CountDownLatch formatReady = new CountDownLatch(1);
    private final CountDownLatch receiverFinished = new CountDownLatch(1);

    private RecordingMuxer.Track outputTrack;
    private RecordingTimeline timeline;
    private LocalServerSocket serverSocket;
    private LocalSocket socket;
    private Process helperProcess;
    private Thread receiverThread;
    private File helperFile;
    private volatile DataOutputStream helperControl;
    private volatile boolean stopRequested;
    private volatile boolean released;
    private boolean formatReceived;
    private boolean started;
    private boolean sourceClockResolved;
    private long sourceToMonotonicOffsetNanos;
    private long lastWrittenPresentationTimeUs = -1L;
    private long encodedBytesWritten;

    PrimeCapVideoRecorder(Context context, int width, int height, int bitRate,
            int frameRate, long maximumFileSize, Listener listener) {
        this.context = context.getApplicationContext();
        this.maxSize = Math.max(width, height);
        this.bitRate = bitRate;
        this.frameRate = frameRate;
        this.maximumFileSize = maximumFileSize;
        this.listener = listener;
    }

    synchronized void prepare() throws IOException {
        if (helperFile != null) {
            return;
        }
        helperFile = deployHelper();
    }

    synchronized void setOutputTrack(RecordingMuxer.Track track) {
        if (started || outputTrack != null || track == null) {
            throw new IllegalStateException("Video output track cannot be changed");
        }
        outputTrack = track;
    }

    synchronized void start(RecordingTimeline recordingTimeline) throws IOException {
        if (started || outputTrack == null || recordingTimeline == null) {
            throw new IllegalStateException("Video recorder is not configured");
        }
        prepare();
        timeline = recordingTimeline;
        started = true;
        String socketName = "primecap_" + UUID.randomUUID().toString().replace("-", "");
        serverSocket = new LocalServerSocket(socketName);
        receiverThread = new Thread(() -> receive(socketName), "PrimeCapVideoReceiver");
        receiverThread.start();

        boolean ready;
        try {
            ready = formatReady.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminateHelper();
            closeSockets();
            throw new IOException("Interrupted while starting PrimeCap video", error);
        }
        if (!ready) {
            terminateHelper();
            closeSockets();
            throw new IOException("Timed out waiting for the PrimeCap video format");
        }
        Exception startupFailure = failure.get();
        if (startupFailure != null) {
            terminateHelper();
            closeSockets();
            throw new IOException("Unable to start privileged video capture", startupFailure);
        }
    }

    void pause() {
        // The helper deliberately keeps encoding; RecordingTimeline drops paused samples.
    }

    void resume() {
        // No helper state changes are needed when the pause-free timeline resumes.
    }

    synchronized void requestStop() {
        if (!started || stopRequested) {
            return;
        }
        stopRequested = true;
        DataOutputStream control = helperControl;
        if (control != null) {
            try {
                control.writeByte(1);
                control.flush();
            } catch (IOException error) {
                Log.w(TAG, "Unable to send helper stop request", error);
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
            terminateHelper();
            closeSockets();
            throw new IOException("Timed out while stopping the PrimeCap helper");
        }
        Exception receiverFailure = failure.get();
        if (receiverFailure != null) {
            throw new IOException("PrimeCap video capture failed", receiverFailure);
        }
    }

    synchronized void release() {
        released = true;
        requestStop();
        terminateHelper();
        closeSockets();
    }

    private void receive(String socketName) {
        boolean cleanEnd = false;
        try {
            launchHelper(socketName);
            socket = serverSocket.accept();
            helperControl = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            if (input.readInt() != MAGIC || input.readInt() != PROTOCOL_VERSION) {
                throw new IOException("Unsupported PrimeCap helper protocol");
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
                    throw new IOException("PrimeCap helper: " + new String(message, "UTF-8"));
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
                recordFailure(new IOException("PrimeCap helper disconnected unexpectedly"));
            }
            formatReady.countDown();
            outputTrack.finish();
            terminateHelper();
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
        Log.i(TAG, "Privileged H.264 format: " + width + "x" + height);
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
        long adjustedPtsUs = adjustPresentationTime(sourcePtsUs);
        if (adjustedPtsUs < 0) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        info.set(0, sample.length, adjustedPtsUs,
                flags & (MediaCodec.BUFFER_FLAG_KEY_FRAME | MediaCodec.BUFFER_FLAG_PARTIAL_FRAME));
        outputTrack.writeSampleData(ByteBuffer.wrap(sample), info);
        encodedBytesWritten += sample.length;
        if ((adjustedPtsUs >= MAX_DURATION_US || encodedBytesWritten >= maximumFileSize)
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
                Log.w(TAG, "Helper timestamps use a different origin; applying a clock offset");
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

    private void launchHelper(String socketName) throws IOException {
        String command = "CLASSPATH=" + shellQuote(helperFile.getAbsolutePath())
                + " app_process / com.genymobile.scrcpy.Server primecap "
                + socketName + " " + maxSize + " " + bitRate + " "
                + String.format(Locale.US, "%.3f", (float) frameRate);
        helperProcess = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        drainProcessOutput(helperProcess.getInputStream());
    }

    private void drainProcessOutput(InputStream stream) {
        Thread logger = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = stream.read(buffer)) >= 0) {
                    if (count > 0) {
                        Log.d(TAG, new String(buffer, 0, count));
                    }
                }
            } catch (IOException ignored) {
                // Process teardown closes the stream.
            }
        }, "PrimeCapHelperLog");
        logger.setDaemon(true);
        logger.start();
    }

    private File deployHelper() throws IOException {
        File destination = new File(context.getCodeCacheDir(), HELPER_ASSET);
        File temporary = new File(destination.getPath() + ".new");
        try (InputStream input = context.getAssets().open(HELPER_ASSET);
                FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Unable to update the PrimeCap helper");
        }
        if (!temporary.renameTo(destination) || !destination.setReadable(true, false)) {
            throw new IOException("Unable to install the PrimeCap helper");
        }
        return destination;
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

    private synchronized void terminateHelper() {
        Process process = helperProcess;
        helperProcess = null;
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
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
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
