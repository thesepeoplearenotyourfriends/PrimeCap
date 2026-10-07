package com.openrecorder.app;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Receives shell-daemon video output and writes it directly to RecordingMuxer. */
final class PrimeCapVideoRecorder {
    interface Listener {
        void onLimitReached();
        void onFailure(Exception error);
    }

    private static final String TAG = "PrimeCapVideo";
    private static final String RELAY_ASSET = "primecap-relay";
    private static final String RELAY_PATH = "/data/local/tmp/primecap-relay";
    private static final String DAEMON_ASSET = "primecap-video-daemon";
    private static final String DAEMON_PATH = "/data/local/tmp/primecap-video-daemon";
    private static final String DAEMON_PID_PATH = "/data/local/tmp/primecap-video-daemon.pid";
    private static final String LAUNCHER_ASSET = "primecap-launcher";
    private static final String LAUNCHER_PATH = "/data/local/tmp/primecap-launcher";
    private static final int MAGIC = 0x50434150;
    private static final int PROTOCOL_VERSION = 6;
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;
    private static final int COMMAND_REQUEST_SYNC_FRAME = 3;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final long STOP_TIMEOUT_MS = 8_000L;
    private static final long DAEMON_EXIT_TIMEOUT_MS = 2_000L;
    // Empirical discard guard (unknown root cause), not a platform/encoder requirement.
    static final int MINIMUM_WARMUP_SECONDS = PrimeCapPreparationStages.MINIMUM_PRIMING_SECONDS;
    private static final long MINIMUM_WARMUP_NANOS = TimeUnit.SECONDS.toNanos(
            MINIMUM_WARMUP_SECONDS);
    private static final long QUIET_PERIOD_NANOS = TimeUnit.SECONDS.toNanos(1L);
    private static final long SYNC_RETRY_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long KEYFRAME_TIMEOUT_MS = 30_000L;
    // Keep sample-arrival headroom beyond the discard guard; readiness has its own wait.
    private static final long MINIMUM_WARMUP_TIMEOUT_MS =
            TimeUnit.NANOSECONDS.toMillis(MINIMUM_WARMUP_NANOS) + 5_000L;
    private static final long SOURCE_CLOCK_TOLERANCE_NANOS = 30_000_000_000L;
    private static final int RELAY_LOG_LIMIT = 16 * 1024;
    private static final String LAUNCHER_EXEC_PREFIX = "PrimeCap launcher exec";
    private static final String DAEMON_STDOUT_CLOSED = "\u0000";
    private static final Pattern DAEMON_READY = Pattern.compile(
            "^PrimeCap video daemon ready on @primecap_video_daemon "
                    + "\\(pid=([0-9]+), uid=([0-9]+)\\)$");

    private final Context context;
    private final int maxSize;
    private final int bitRate;
    private final int videoCodec;
    private final int frameRate;
    private final long maximumFileSize;
    private final Listener listener;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicBoolean limitNotified = new AtomicBoolean();
    private final CountDownLatch formatReady = new CountDownLatch(1);
    private final CountDownLatch receiverFinished = new CountDownLatch(1);
    private final CountDownLatch timelineReady = new CountDownLatch(1);
    private final CountDownLatch recordingBoundaryReady = new CountDownLatch(1);
    private final CountDownLatch minimumWarmupReady = new CountDownLatch(1);
    private final CountDownLatch readinessKeyframeReady = new CountDownLatch(1);
    private final PrimeCapPreparationStages preparationStages = new PrimeCapPreparationStages(
            MINIMUM_WARMUP_NANOS, SYNC_RETRY_NANOS, QUIET_PERIOD_NANOS);

    private volatile RecordingMuxer.Track outputTrack;
    private MediaFormat preparedFormat;
    private RecordingTimeline timeline;
    private Process relayProcess;
    private Process daemonProcess;
    private Thread receiverThread;
    private File relayFile;
    private File daemonFile;
    private boolean daemonOwned;
    private int launchedDaemonPid = -1;
    private final StringBuilder recentDaemonErrors = new StringBuilder();
    private final StringBuilder recentRelayErrors = new StringBuilder();
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
    private long warmupStartedNanos;
    private long recordingBoundaryNanos;
    private long discardedSamples;
    private long discardedBytes;
    private boolean firstAcceptedSample = true;

    PrimeCapVideoRecorder(Context context, int width, int height, int bitRate, int videoCodec,
            int frameRate, long maximumFileSize, Listener listener) {
        this.context = context.getApplicationContext();
        this.maxSize = Math.max(width, height);
        this.bitRate = bitRate;
        this.videoCodec = RecordingOptions.normalizeVideoCodec(videoCodec);
        this.frameRate = frameRate;
        this.maximumFileSize = maximumFileSize;
        this.listener = listener;
    }

    void prepare() throws IOException {
        if (relayFile == null) {
            relayFile = deployAsset(RELAY_ASSET, RELAY_PATH);
            daemonFile = deployAsset(DAEMON_ASSET, DAEMON_PATH);
            deployAsset(LAUNCHER_ASSET, LAUNCHER_PATH, "0755");
            if (released) {
                throw new IOException("PrimeCap daemon startup was cancelled");
            }
            launchDaemon();
        }
    }

    synchronized void setOutputTrack(RecordingMuxer.Track track) throws IOException {
        if (timeline != null || outputTrack != null || track == null) {
            throw new IllegalStateException("Video output track cannot be changed");
        }
        outputTrack = track;
        if (preparedFormat != null) track.setFormat(preparedFormat);
    }

    void start() throws IOException {
        if (started || released) {
            throw new IllegalStateException("Video recorder is not configured");
        }
        started = true;
        receiverThread = new Thread(this::receive, "PrimeCapVideoReceiver");
        receiverThread.start();

        try {
            formatReady.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            terminateRelay();
            throw new IOException("Interrupted while starting PrimeCap video", error);
        }
        Exception startupFailure = failure.get();
        if (startupFailure != null) {
            terminateRelay();
            terminateOwnedDaemon();
            if (startupFailure instanceof VideoDaemonUnavailableException) {
                throw (VideoDaemonUnavailableException) startupFailure;
            }
            throw new IOException("Unable to start video daemon capture", startupFailure);
        }
    }

    synchronized void arm(RecordingTimeline recordingTimeline) {
        if (!started || timeline != null || recordingTimeline == null
                || recordingBoundaryReady.getCount() != 0) {
            throw new IllegalStateException("Video recorder is not ready to arm");
        }
        timeline = recordingTimeline;
        timelineReady.countDown();
    }

    void awaitReady() throws IOException {
        try {
            awaitPreparationStage(minimumWarmupReady, MINIMUM_WARMUP_TIMEOUT_MS,
                    "minimum warmup");
            awaitPreparationStage(readinessKeyframeReady, KEYFRAME_TIMEOUT_MS,
                    "readiness keyframe");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while priming PrimeCap video", error);
        }
        if (released) throw new IOException("Priming cancelled");
    }

    boolean beginCountdown() {
        return !released && preparationStages.beginCountdown(System.nanoTime());
    }

    long awaitRecordingBoundary() throws IOException {
        try {
            awaitPreparationStage(recordingBoundaryReady,
                    TimeUnit.NANOSECONDS.toMillis(QUIET_PERIOD_NANOS) + KEYFRAME_TIMEOUT_MS,
                    "recording-boundary keyframe");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while preparing PrimeCap video", error);
        }
        Exception preparationFailure = failure.get();
        if (preparationFailure != null) {
            throw new IOException("Unable to prepare PrimeCap video", preparationFailure);
        }
        if (recordingBoundaryNanos <= 0L) {
            long elapsedNanos = warmupStartedNanos == 0L
                    ? 0L
                    : Math.max(0L, System.nanoTime() - warmupStartedNanos);
            Log.w(TAG, "PrimeCap warmup timed out: duration="
                    + TimeUnit.NANOSECONDS.toMillis(elapsedNanos) + " ms"
                    + ", discardedSamples=" + discardedSamples
                    + ", discardedBytes=" + discardedBytes
                    + ", keyframeWait=" + TimeUnit.NANOSECONDS.toMillis(
                            Math.max(0L, elapsedNanos - MINIMUM_WARMUP_NANOS)) + " ms"
                    + ", codec=" + codecName()
                    + ", bitrate=" + bitRate + " bps");
            terminateRelay();
            terminateOwnedDaemon();
            throw new IOException("Timed out preparing the recording boundary");
        }
        return recordingBoundaryNanos;
    }

    private void awaitPreparationStage(CountDownLatch latch, long timeoutMs, String stage)
            throws InterruptedException, IOException {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            terminateRelay();
            terminateOwnedDaemon();
            throw new IOException("Timed out during PrimeCap " + stage);
        }
        Exception preparationFailure = failure.get();
        if (preparationFailure != null) {
            throw new IOException("Unable to prepare PrimeCap video", preparationFailure);
        }
    }

    void pause() {
        // The daemon deliberately keeps encoding; RecordingTimeline drops paused samples.
    }

    void resume() {
        // No daemon state changes are needed when the pause-free timeline resumes.
    }

    synchronized void cancelPreparation() {
        released = true;
        preparationStages.cancel();
        timelineReady.countDown();
        formatReady.countDown();
        minimumWarmupReady.countDown();
        readinessKeyframeReady.countDown();
        recordingBoundaryReady.countDown();
        requestStop();
        terminateRelay();
        terminateOwnedDaemon();
    }

    synchronized void onPreparationOverlayCleared() {
        long nowNanos = System.nanoTime();
        if (!released && preparationStages.onCountdownCleared(nowNanos)) {
            Log.i(TAG, "Final countdown ended at " + nowNanos + " ns; Toast cleared");
        }
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
            terminateRelay();
            terminateOwnedDaemon();
            throw new IOException("Timed out while stopping the PrimeCap video daemon");
        }
        Exception receiverFailure = failure.get();
        if (receiverFailure != null) {
            throw new IOException("PrimeCap video capture failed", receiverFailure);
        }
    }

    synchronized void release() {
        released = true;
        preparationStages.cancel();
        timelineReady.countDown();
        formatReady.countDown();
        minimumWarmupReady.countDown();
        readinessKeyframeReady.countDown();
        recordingBoundaryReady.countDown();
        requestStop();
        terminateRelay();
        if (!started || receiverFinished.getCount() == 0) {
            terminateOwnedDaemon();
        }
    }

    private void receive() {
        boolean cleanEnd = false;
        try {
            launchRelay();
            daemonControl = new DataOutputStream(
                    new BufferedOutputStream(relayProcess.getOutputStream()));
            DataInputStream input = new DataInputStream(
                    new BufferedInputStream(relayProcess.getInputStream()));
            daemonControl.writeInt(MAGIC);
            daemonControl.writeInt(PROTOCOL_VERSION);
            daemonControl.writeByte(COMMAND_START);
            daemonControl.writeInt(maxSize);
            daemonControl.writeInt(bitRate);
            daemonControl.writeInt(videoCodec);
            daemonControl.writeFloat((float) frameRate);
            daemonControl.flush();
            if (input.readInt() != MAGIC || input.readInt() != PROTOCOL_VERSION) {
                throw new IOException("Unsupported PrimeCap video daemon protocol");
            }
            daemonPid = input.readInt();
            daemonUid = input.readInt();
            daemonOwned = daemonPid == launchedDaemonPid;
            Log.i(TAG, "PrimeCap startup relay connected at "
                    + SystemClock.elapsedRealtime() + " ms: pid=" + daemonPid
                    + " uid=" + daemonUid);
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
                recordFailure(withRelayDiagnostics(error));
            }
        } finally {
            if (!cleanEnd && !released && failure.get() == null) {
                recordFailure(new IOException("PrimeCap video daemon disconnected unexpectedly"));
            }
            formatReady.countDown();
            recordingBoundaryReady.countDown();
            minimumWarmupReady.countDown();
            readinessKeyframeReady.countDown();
            if (outputTrack != null) outputTrack.finish();
            terminateRelay();
            if (cleanEnd) {
                awaitOwnedDaemonExit();
            } else {
                terminateOwnedDaemon();
            }
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
            throw new IOException("Invalid PrimeCap video format metadata");
        }
        byte[] csd0 = readBytes(input, csd0Length);
        int csd1Length = input.readInt();
        if (csd1Length < 0 || csd0Length + csd1Length != length - 16) {
            throw new IOException("Invalid PrimeCap video codec configuration");
        }
        byte[] csd1 = readBytes(input, csd1Length);
        String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0));
        if (csd1.length > 0) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1));
        }
        preparedFormat = format;
        formatReceived = true;
        warmupStartedNanos = System.nanoTime();
        preparationStages.start(warmupStartedNanos);
        Log.i(TAG, "Daemon " + codecName() + " format: " + width + "x" + height);
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
        long nowNanos = System.nanoTime();
        boolean keyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        if (recordingBoundaryNanos == 0L) {
            PrimeCapPreparationStages.Action action = preparationStages.onSample(
                    nowNanos, keyFrame);
            if (action == PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC) {
                if (minimumWarmupReady.getCount() != 0L) {
                    Log.i(TAG, "Minimum warmup completed after "
                            + TimeUnit.NANOSECONDS.toMillis(nowNanos - warmupStartedNanos)
                            + " ms");
                    minimumWarmupReady.countDown();
                }
                requestSyncFrame("readiness", preparationStages.getReadinessRequestNanos());
            } else if (action == PrimeCapPreparationStages.Action.READY) {
                Log.i(TAG, "Readiness keyframe latency=" + TimeUnit.NANOSECONDS.toMillis(
                        nowNanos - preparationStages.getReadinessRequestNanos()) + " ms");
                readinessKeyframeReady.countDown();
            } else if (action == PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC) {
                requestSyncFrame("final recording boundary",
                        preparationStages.getFinalRequestNanos());
            }
            if (action != PrimeCapPreparationStages.Action.COMPLETE) {
                discardedSamples++;
                discardedBytes += sampleLength;
                return;
            }
            recordingBoundaryNanos = nowNanos;
            long readinessWaitNanos = Math.max(0L,
                    preparationStages.getReadinessReachedNanos()
                            - preparationStages.getReadinessRequestNanos());
            long startKeyframeWaitNanos = Math.max(0L,
                    nowNanos - preparationStages.getFinalRequestNanos());
            Log.i(TAG, "Final keyframe latency="
                    + TimeUnit.NANOSECONDS.toMillis(startKeyframeWaitNanos) + " ms");
            Log.i(TAG, "PrimeCap warmup: duration="
                    + TimeUnit.NANOSECONDS.toMillis(
                            preparationStages.getReadinessReachedNanos()
                                    - warmupStartedNanos) + " ms"
                    + ", discardedSamples=" + discardedSamples
                    + ", discardedBytes=" + discardedBytes
                    + ", readinessKeyframeWait="
                    + TimeUnit.NANOSECONDS.toMillis(readinessWaitNanos) + " ms"
                    + ", finalCountdown="
                    + TimeUnit.NANOSECONDS.toMillis(preparationStages.getCountdownEndedNanos()
                            - preparationStages.getCountdownStartedNanos()) + " ms"
                    + ", startKeyframeWait="
                    + TimeUnit.NANOSECONDS.toMillis(startKeyframeWaitNanos) + " ms"
                    + ", codec=" + codecName()
                    + ", bitrate=" + bitRate + " bps");
            recordingBoundaryReady.countDown();
        }
        try {
            timelineReady.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while arming the recording timeline", error);
        }
        if (released || timeline == null) {
            return;
        }
        long adjustedPtsUs;
        if (firstAcceptedSample) {
            sourceToMonotonicOffsetNanos = timeline.getStartedAtNanos()
                    - sourcePtsUs * 1_000L;
            sourceClockResolved = true;
            firstAcceptedSample = false;
            adjustedPtsUs = 0L;
            lastWrittenPresentationTimeUs = adjustedPtsUs;
        } else {
            adjustedPtsUs = adjustPresentationTime(sourcePtsUs);
        }
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

    private String codecName() {
        return "H.264";
    }

    private synchronized void requestSyncFrame(String stage, long requestedAtNanos)
            throws IOException {
        DataOutputStream control = daemonControl;
        if (control == null) {
            throw new IOException("PrimeCap daemon control channel is unavailable");
        }
        control.writeByte(COMMAND_REQUEST_SYNC_FRAME);
        control.flush();
        Log.i(TAG, "Requested " + codecName() + " sync frame for " + stage
                + " at " + requestedAtNanos + " ns");
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
            minimumWarmupReady.countDown();
            readinessKeyframeReady.countDown();
            recordingBoundaryReady.countDown();
            try {
                listener.onFailure(error);
            } catch (RuntimeException listenerError) {
                Log.w(TAG, "Video failure listener failed", listenerError);
            }
        }
    }

    private void launchRelay() throws IOException {
        String command = "CLASSPATH=" + shellQuote(relayFile.getAbsolutePath())
                + " app_process / com.genymobile.scrcpy.Server primecap-relay";
        relayProcess = new ProcessBuilder("su", "-c", command).start();
        drainRelayErrors(relayProcess.getErrorStream());
    }

    private File deployAsset(String assetName, String destinationPath) throws IOException {
        return deployAsset(assetName, destinationPath, "0644");
    }

    private File deployAsset(String assetName, String destinationPath, String mode)
            throws IOException {
        File source = new File(context.getCodeCacheDir(), assetName + ".stage");
        try (InputStream input = context.getAssets().open(assetName);
                FileOutputStream output = new FileOutputStream(source)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        String temporaryPath = destinationPath + ".new";
        String install = "cp " + shellQuote(source.getAbsolutePath()) + " "
                + shellQuote(temporaryPath) + " && chmod " + mode + " "
                + shellQuote(temporaryPath)
                + " && mv " + shellQuote(temporaryPath) + " " + shellQuote(destinationPath);
        Process process = new ProcessBuilder("su", "-c", install).start();
        String errors = readText(process.getErrorStream());
        try {
            if (process.waitFor() != 0) {
                throw new IOException("Unable to stage " + assetName + ": " + errors.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while staging PrimeCap relay", error);
        } finally {
            if (!source.delete()) {
                Log.w(TAG, "Unable to remove temporary PrimeCap asset");
            }
        }
        return new File(destinationPath);
    }

    private void launchDaemon() throws IOException {
        Log.i(TAG, "PrimeCap startup launch requested at "
                + SystemClock.elapsedRealtime() + " ms");
        try {
            launchDaemonAttempt();
        } catch (IOException error) {
            terminateOwnedDaemon();
            if (released) {
                throw new IOException("PrimeCap daemon startup was cancelled", error);
            }
            if (!isAddressAlreadyInUse(error)) {
                throw error;
            }
            Log.w(TAG, "PrimeCap socket was already bound; running one stale-daemon migration");
            cleanupStaleDaemon();
            synchronized (recentDaemonErrors) {
                recentDaemonErrors.setLength(0);
            }
            Log.i(TAG, "PrimeCap startup launch requested at "
                    + SystemClock.elapsedRealtime() + " ms (migration retry)");
            try {
                launchDaemonAttempt();
            } catch (IOException retryError) {
                terminateOwnedDaemon();
                throw retryError;
            }
        }
    }

    private void launchDaemonAttempt() throws IOException {
        launchedDaemonPid = -1;
        daemonOwned = false;
        String command = shellQuote(LAUNCHER_PATH) + " "
                + shellQuote(daemonFile.getAbsolutePath()) + " "
                + shellQuote(DAEMON_PID_PATH);
        Process process = new ProcessBuilder("su", "-c", command).start();
        synchronized (this) {
            if (released) {
                process.destroyForcibly();
                throw new IOException("PrimeCap daemon startup was cancelled");
            }
            daemonProcess = process;
        }
        Thread diagnostics = drainDaemonErrors(process.getErrorStream());
        waitForDaemonReady(process.getInputStream(), diagnostics);
    }

    private void waitForDaemonReady(InputStream stdout, Thread diagnostics) throws IOException {
        LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        drainDaemonOutput(stdout, lines);
        try {
            while (true) {
                String line = lines.take();
                if (released) {
                    throw new IOException("PrimeCap daemon startup was cancelled");
                }
                if (DAEMON_STDOUT_CLOSED.equals(line)) {
                    daemonProcess.waitFor();
                    diagnostics.join();
                    throw daemonLaunchFailure("launcher exited with status "
                            + daemonProcess.exitValue() + " before READY");
                }
                if (line.startsWith(LAUNCHER_EXEC_PREFIX)) {
                    Log.i(TAG, "PrimeCap startup launcher exec at "
                            + SystemClock.elapsedRealtime() + " ms: " + line);
                }
                Matcher ready = DAEMON_READY.matcher(line);
                if (ready.matches()) {
                    launchedDaemonPid = Integer.parseInt(ready.group(1));
                    Log.i(TAG, "PrimeCap startup daemon READY received at "
                            + SystemClock.elapsedRealtime() + " ms: " + line);
                    return;
                }
                Log.i(TAG, "Daemon stdout: " + line);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting PrimeCap daemon", error);
        } catch (NumberFormatException error) {
            throw daemonLaunchFailure("daemon READY contained an invalid PID");
        }
    }

    private void drainDaemonOutput(InputStream stream, LinkedBlockingQueue<String> lines) {
        Thread logger = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.offer(line);
                }
            } catch (IOException ignored) {
                // Process teardown closes stdout.
            } finally {
                lines.offer(DAEMON_STDOUT_CLOSED);
            }
        }, "PrimeCapDaemonOutput");
        logger.setDaemon(true);
        logger.start();
    }

    private boolean isAddressAlreadyInUse(IOException error) {
        String message = error.getMessage();
        return message != null && (message.contains("Address already in use")
                || message.contains("EADDRINUSE"));
    }

    private void cleanupStaleDaemon() throws IOException {
        Process cleanup = new ProcessBuilder("su", "-c",
                PrimeCapDaemonLifecycle.staleCleanupCommand(DAEMON_PID_PATH)).start();
        String errors = readText(cleanup.getErrorStream());
        try {
            if (cleanup.waitFor() != 0) {
                throw new IOException("Unable to clean up stale PrimeCap daemon: "
                        + errors.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            cleanup.destroyForcibly();
            throw new IOException("Interrupted while cleaning up stale PrimeCap daemon", error);
        }
    }

    private IOException daemonLaunchFailure(String reason) {
        String diagnostics;
        synchronized (recentDaemonErrors) {
            diagnostics = recentDaemonErrors.toString().trim();
        }
        return new VideoDaemonUnavailableException("Video daemon unavailable: " + reason
                + (diagnostics.isEmpty() ? "" : ": " + diagnostics));
    }

    private Thread drainDaemonErrors(InputStream stream) {
        Thread logger = new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    String message = new String(buffer, 0, count);
                    synchronized (recentDaemonErrors) {
                        recentDaemonErrors.append(message);
                        int excess = recentDaemonErrors.length() - RELAY_LOG_LIMIT;
                        if (excess > 0) {
                            recentDaemonErrors.delete(0, excess);
                        }
                    }
                    Log.w(TAG, "Daemon stderr: " + message.trim());
                }
            } catch (IOException ignored) {
            }
        }, "PrimeCapDaemonDiagnostics");
        logger.setDaemon(true);
        logger.start();
        return logger;
    }

    private synchronized void terminateOwnedDaemon() {
        Process process = daemonProcess;
        daemonProcess = null;
        if (process == null) {
            return;
        }
        int pidToStop = daemonOwned ? daemonPid : launchedDaemonPid;
        if (pidToStop <= 0) {
            pidToStop = readLauncherPidFile();
        }
        if (pidToStop > 0) {
            try {
                Process killer = new ProcessBuilder("su", "-c", "kill " + pidToStop).start();
                if (!killer.waitFor(2, TimeUnit.SECONDS)) {
                    killer.destroyForcibly();
                }
            } catch (IOException error) {
                Log.w(TAG, "Unable to stop owned PrimeCap daemon pid=" + pidToStop, error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private int readLauncherPidFile() {
        Process reader = null;
        try {
            reader = new ProcessBuilder("su", "-c",
                    "cat " + shellQuote(DAEMON_PID_PATH) + " 2>/dev/null").start();
            String value = readText(reader.getInputStream()).trim();
            if (reader.waitFor(1, TimeUnit.SECONDS) && reader.exitValue() == 0) {
                return Integer.parseInt(value);
            }
        } catch (IOException | NumberFormatException error) {
            Log.w(TAG, "Unable to read launcher PID file", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (reader != null && reader.isAlive()) {
                reader.destroyForcibly();
            }
        }
        return -1;
    }

    private void awaitOwnedDaemonExit() {
        Process process;
        synchronized (this) {
            process = daemonProcess;
        }
        if (process == null) {
            return;
        }
        try {
            if (process.waitFor(DAEMON_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                synchronized (this) {
                    if (daemonProcess == process) {
                        daemonProcess = null;
                    }
                }
                return;
            }
            recordFailure(new IOException(
                    "Timed out waiting for the PrimeCap video daemon to exit"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            recordFailure(new IOException(
                    "Interrupted while waiting for the PrimeCap video daemon to exit", error));
        }
        terminateOwnedDaemon();
    }

    private void drainRelayErrors(InputStream stream) {
        Thread logger = new Thread(() -> {
            byte[] buffer = new byte[1024];
            try {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    String message = new String(buffer, 0, count);
                    synchronized (recentRelayErrors) {
                        recentRelayErrors.append(message);
                        int excess = recentRelayErrors.length() - RELAY_LOG_LIMIT;
                        if (excess > 0) {
                            recentRelayErrors.delete(0, excess);
                        }
                    }
                    Log.w(TAG, "Relay stderr: " + message.trim());
                }
            } catch (IOException ignored) {
                // Relay teardown closes stderr.
            }
        }, "PrimeCapRelayDiagnostics");
        logger.setDaemon(true);
        logger.start();
    }

    private IOException withRelayDiagnostics(Exception cause) {
        String diagnostics;
        synchronized (recentRelayErrors) {
            diagnostics = recentRelayErrors.toString().trim();
        }
        String message = "Video daemon unavailable through relay"
                + (diagnostics.isEmpty() ? "" : ": " + diagnostics);
        Log.e(TAG, message, cause);
        return new VideoDaemonUnavailableException(message, cause);
    }

    private synchronized void terminateRelay() {
        daemonControl = null;
        Process process = relayProcess;
        relayProcess = null;
        if (process == null) {
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
        try {
            if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
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

    private static String readText(InputStream input) throws IOException {
        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.append(new String(buffer, 0, count));
        }
        return output.toString();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
