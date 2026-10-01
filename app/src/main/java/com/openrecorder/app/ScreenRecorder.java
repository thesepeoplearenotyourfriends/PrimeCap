package com.openrecorder.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class ScreenRecorder {
    interface Listener {
        void onProjectionStopped();
        void onRecorderLimitReached();
        void onRecorderError(Exception error);
        void onAudioCaptureFailed();
        void onPreparationFinalCountdownStarted(int durationSeconds);
    }

    private static final String TAG = "ScreenRecorder";
    private static final long MAX_FILE_SIZE = 5_000_000_000L;
    private static final long MIN_FILE_SIZE = 32L * 1024L * 1024L;
    private static final long STORAGE_RESERVE = 64L * 1024L * 1024L;

    private final Context context;
    private final int resultCode;
    private final Intent resultData;
    private final AudioSource audioSource;
    private final int audioSampleRate;
    private final int videoResolution;
    private final int videoFrameRate;
    private final boolean force16By9Letterboxing;
    private final int videoBitrate;
    private final int videoCodec;
    private final String namingPattern;
    private final int recordingOrientation;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final MediaProjection.Callback projectionCallback;
    private MediaProjection projection;
    private volatile PrimeCapVideoRecorder videoRecorder;
    private InternalAudioRecorder audioRecorder;
    private RecordingMuxer recordingMuxer;
    private RecordingMuxer.Track videoOutputTrack;
    private RecordingTimeline timeline;
    private Uri outputUri;
    private CaptureSize preparedCaptureSize;
    private boolean prepared;
    private boolean started;
    private boolean paused;
    private boolean stopped;
    private boolean audioFailed;
    private boolean outputFinalized;
    private boolean outputPublished;

    ScreenRecorder(
            Context context,
            int resultCode,
            Intent resultData,
            AudioSource audioSource,
            int audioSampleRate,
            int videoResolution,
            int videoFrameRate,
            boolean force16By9Letterboxing,
            int videoBitrate,
            int videoCodec,
            String namingPattern,
            int recordingOrientation,
            Listener listener) {
        this.context = context.getApplicationContext();
        this.resultCode = resultCode;
        this.resultData = resultData;
        this.audioSource = audioSource;
        this.audioSampleRate = RecordingOptions.normalizeSampleRate(audioSampleRate);
        this.videoResolution = RecordingOptions.normalizeVideoResolution(videoResolution);
        this.videoFrameRate = RecordingOptions.normalizeVideoFrameRate(videoFrameRate);
        this.force16By9Letterboxing = force16By9Letterboxing;
        this.videoBitrate = RecordingOptions.normalizeVideoBitrate(videoBitrate);
        this.videoCodec = RecordingOptions.normalizeVideoCodec(videoCodec);
        this.namingPattern = RecordingOptions.normalizeNamingPattern(namingPattern);
        this.recordingOrientation = RecordingOptions.normalizeOrientation(recordingOrientation);
        this.listener = listener;
        this.projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                ScreenRecorder.this.listener.onProjectionStopped();
            }

        };
    }

    static void deleteStaleTemporaryFiles(Context context) {
        File[] cacheFiles = context.getCacheDir().listFiles();
        if (cacheFiles == null) {
            return;
        }
        for (File file : cacheFiles) {
            String name = file.getName();
            if (name.startsWith("screen-video-")
                    || name.startsWith("screen-audio-")
                    || name.startsWith("screen-final-")) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete stale temporary file: " + name);
                }
            }
        }
    }

    static boolean isVideoCodecSupported(int requestedCodec) {
        int codec = RecordingOptions.normalizeVideoCodec(requestedCodec);
        return codec == RecordingOptions.VIDEO_CODEC_H264
                || codec == RecordingOptions.VIDEO_CODEC_H265;
    }

    synchronized void prepare() throws IOException {
        if (prepared) {
            return;
        }
        if (started || stopped) {
            throw new IllegalStateException("ScreenRecorder is single-use");
        }
        MediaProjectionManager manager = context.getSystemService(MediaProjectionManager.class);
        projection = manager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            throw new IOException("MediaProjection permission was not granted");
        }
        projection.registerCallback(projectionCallback, mainHandler);

        preparedCaptureSize = getCaptureSize();
        prepareVideoRecorder(preparedCaptureSize);

        if (audioSource != AudioSource.NONE) {
            try {
                audioRecorder = new InternalAudioRecorder(
                        context,
                        projection,
                        audioSource,
                        audioSampleRate);
                audioRecorder.prepare();
            } catch (Exception error) {
                disableAudio(error);
            }
        }
        prepared = true;
    }

    synchronized void start() throws IOException {
        if (started || stopped) {
            throw new IllegalStateException("ScreenRecorder is single-use");
        }
        prepare();
        prepareOutput();
        started = true;

        // Daemon connection and FORMAT negotiation are preparation,
        // not recorded time. Arm the shared timeline only once video is usable.
        videoRecorder.start();
        long recordingBoundaryNanos = videoRecorder.awaitRecordingBoundary();
        timeline = new RecordingTimeline(recordingBoundaryNanos);
        if (audioRecorder != null) {
            try {
                audioRecorder.start(timeline);
            } catch (Exception error) {
                disableAudio(error);
            }
        }
        videoRecorder.arm(timeline);
    }

    synchronized void pause() throws IOException {
        if (!started || stopped) {
            throw new IllegalStateException("Recording is not active");
        }
        if (paused) {
            return;
        }

        timeline.pause(System.nanoTime());
        videoRecorder.pause();
        paused = true;

        if (audioRecorder != null) {
            try {
                audioRecorder.pause();
            } catch (Exception error) {
                disableAudio(error);
            }
        }
    }

    synchronized void resume() throws IOException {
        if (!started || stopped) {
            throw new IllegalStateException("Recording is not active");
        }
        if (!paused) {
            return;
        }

        timeline.resume(System.nanoTime());
        videoRecorder.resume();
        paused = false;

        if (audioRecorder != null) {
            try {
                audioRecorder.resume();
            } catch (Exception error) {
                disableAudio(error);
            }
        }
    }

    synchronized void stop() throws IOException {
        if (stopped) {
            return;
        }
        stopped = true;
        paused = false;
        if (timeline != null) {
            timeline.stop(System.nanoTime());
        }
        if (videoRecorder != null) {
            videoRecorder.requestStop();
        }
        if (audioRecorder != null) {
            audioRecorder.requestStop();
        }

        IOException failure = null;
        if (videoRecorder != null) {
            try {
                videoRecorder.awaitStopped();
            } catch (IOException error) {
                failure = error;
            }
        }

        if (audioRecorder != null) {
            try {
                audioRecorder.awaitStopped();
            } catch (IOException error) {
                disableAudio(error);
            }
        }

        if (failure == null && recordingMuxer != null) {
            try {
                recordingMuxer.stop();
                outputFinalized = true;
            } catch (IOException error) {
                failure = error;
            }
        }
        releaseCaptureResources();
        if (failure != null) {
            throw failure;
        }
    }

    void cancelPreparation() {
        PrimeCapVideoRecorder activeVideoRecorder = videoRecorder;
        if (timeline == null && activeVideoRecorder != null) {
            activeVideoRecorder.cancelPreparation();
        }
    }

    void onPreparationOverlayCleared() {
        PrimeCapVideoRecorder activeVideoRecorder = videoRecorder;
        if (timeline == null && activeVideoRecorder != null) {
            activeVideoRecorder.onPreparationOverlayCleared();
        }
    }

    Uri save() throws IOException {
        if (!outputFinalized
                || outputUri == null
                || videoOutputTrack == null
                || videoOutputTrack.getSampleCount() == 0L) {
            throw new IOException("No recorded video was produced");
        }
        ContentResolver resolver = context.getContentResolver();
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
        if (resolver.update(outputUri, ready, null, null) <= 0) {
            throw new IOException("MediaStore did not publish the output item");
        }
        outputPublished = true;
        return outputUri;
    }

    synchronized void release() {
        stopped = true;
        paused = false;
        if (audioRecorder != null) {
            audioRecorder.release();
            audioRecorder = null;
        }
        releaseCaptureResources();
        releaseOutput();
    }

    private void releaseCaptureResources() {
        releaseRecorderResources();
        if (projection != null) {
            try {
                projection.unregisterCallback(projectionCallback);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to unregister MediaProjection callback", error);
            }
            try {
                projection.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to stop MediaProjection", error);
            }
            projection = null;
        }
    }

    private void prepareOutput() throws IOException {
        ContentResolver resolver = context.getContentResolver();
        long now = System.currentTimeMillis();
        String fileName = new SimpleDateFormat(namingPattern, Locale.US).format(new Date(now))
                + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Open Recorder");
        values.put(MediaStore.Video.Media.DATE_TAKEN, now);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri pendingUri = resolver.insert(collection, values);
        if (pendingUri == null) {
            throw new IOException("MediaStore did not create an output item");
        }

        RecordingMuxer pendingMuxer = null;
        RecordingMuxer.Track pendingVideoTrack;
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(pendingUri, "rw")) {
            if (descriptor == null) {
                throw new IOException("MediaStore output file is unavailable");
            }
            pendingMuxer = new RecordingMuxer(descriptor.getFileDescriptor());
            pendingVideoTrack = pendingMuxer.createTrack("video");
            videoRecorder.setOutputTrack(pendingVideoTrack);
            if (audioRecorder != null) {
                audioRecorder.setOutputTrack(pendingMuxer.createTrack("audio"));
            }
        } catch (Exception error) {
            if (pendingMuxer != null) {
                pendingMuxer.release();
            }
            try {
                resolver.delete(pendingUri, null, null);
            } catch (RuntimeException deleteError) {
                error.addSuppressed(deleteError);
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Unable to prepare the recording output", error);
        }
        outputUri = pendingUri;
        recordingMuxer = pendingMuxer;
        videoOutputTrack = pendingVideoTrack;
    }

    private void releaseOutput() {
        if (recordingMuxer != null) {
            recordingMuxer.release();
            recordingMuxer = null;
        }
        if (!outputPublished && outputUri != null) {
            try {
                context.getContentResolver().delete(outputUri, null, null);
            } catch (RuntimeException error) {
                Log.w(TAG, "Unable to remove an incomplete MediaStore item", error);
            }
        }
        outputUri = null;
    }

    private CaptureSize getCaptureSize() {
        WindowManager windowManager = context.getSystemService(WindowManager.class);
        Rect bounds;
        int density;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds = windowManager.getMaximumWindowMetrics().getBounds();
            density = context.getResources().getConfiguration().densityDpi;
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
            bounds = new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            density = metrics.densityDpi;
        }

        return normalizeCaptureSize(bounds.width(), bounds.height(), density);
    }

    private CaptureSize normalizeCaptureSize(int requestedWidth, int requestedHeight, int density) {
        float sourceRefreshRate = getSourceRefreshRate();
        int targetFrameRate = VideoFrameRatePolicy.resolvePreferredFrameRate(
                videoFrameRate,
                sourceRefreshRate);
        VideoEncodingProfile.Layout layout = VideoEncodingProfile.resolve(
                requestedWidth,
                requestedHeight,
                recordingOrientation,
                videoResolution,
                force16By9Letterboxing,
                videoBitrate,
                targetFrameRate);
        return new CaptureSize(
                layout.outputWidth,
                layout.outputHeight,
                layout.contentWidth,
                layout.contentHeight,
                layout.videoBitrate,
                density,
                sourceRefreshRate,
                targetFrameRate);
    }

    private void prepareVideoRecorder(CaptureSize size) throws IOException {
        videoRecorder = new PrimeCapVideoRecorder(
                context,
                size.width,
                size.height,
                size.videoBitrate,
                videoCodec,
                size.targetFrameRate,
                recordingOrientation,
                getMaximumVideoFileSize(),
                new PrimeCapVideoRecorder.Listener() {
                    @Override
                    public void onFinalCountdownStarted(int durationSeconds) {
                        listener.onPreparationFinalCountdownStarted(durationSeconds);
                    }

                    @Override
                    public void onLimitReached() {
                        listener.onRecorderLimitReached();
                    }

                    @Override
                    public void onFailure(Exception error) {
                        listener.onRecorderError(error);
                    }
                });
        videoRecorder.prepare();
        String codecName = videoCodec == RecordingOptions.VIDEO_CODEC_H265 ? "H.265" : "H.264";
        Log.i(TAG, "PrimeCap " + codecName + " profile: maximum="
                + Math.max(size.width, size.height)
                + ", frameRate=" + size.targetFrameRate + " fps"
                + ", target=" + size.videoBitrate + " bps");
    }

    private float getSourceRefreshRate() {
        try {
            DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            Display display = displayManager == null
                    ? null
                    : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return 0f;
            }
            float refreshRate = display.getRefreshRate();
            return Float.isFinite(refreshRate) && refreshRate > 0f ? refreshRate : 0f;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to read the source display refresh rate", error);
            return 0f;
        }
    }

    private void releaseRecorderResources() {
        if (videoRecorder != null) {
            videoRecorder.release();
            videoRecorder = null;
        }
    }

    private void disableAudio(Exception error) {
        boolean notifyListener = !audioFailed;
        audioFailed = true;
        Log.e(TAG, "Audio capture failed; continuing without it", error);
        if (audioRecorder != null) {
            audioRecorder.release();
            audioRecorder = null;
        }
        if (notifyListener) {
            listener.onAudioCaptureFailed();
        }
    }

    private long getMaximumVideoFileSize() throws IOException {
        File storageProbe = context.getExternalFilesDir(null);
        if (storageProbe == null) {
            storageProbe = context.getCacheDir();
        }
        long usableSpace = storageProbe.getUsableSpace();
        long availableSpace = Math.max(0L, usableSpace - STORAGE_RESERVE);
        long safeFileSize = availableSpace;
        if (safeFileSize < MIN_FILE_SIZE) {
            throw new IOException("Not enough storage space to start recording");
        }
        return Math.min(MAX_FILE_SIZE, safeFileSize);
    }

    private static final class CaptureSize {
        final int width;
        final int height;
        final int contentWidth;
        final int contentHeight;
        final int videoBitrate;
        final int densityDpi;
        final float sourceRefreshRate;
        final int targetFrameRate;

        CaptureSize(
                int width,
                int height,
                int contentWidth,
                int contentHeight,
                int videoBitrate,
                int densityDpi,
                float sourceRefreshRate,
                int targetFrameRate) {
            this.width = width;
            this.height = height;
            this.contentWidth = contentWidth;
            this.contentHeight = contentHeight;
            this.videoBitrate = videoBitrate;
            this.densityDpi = densityDpi;
            this.sourceRefreshRate = sourceRefreshRate;
            this.targetFrameRate = targetFrameRate;
        }
    }
}
