package com.openrecorder.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordingService extends Service implements ScreenRecorder.Listener {
    private static final String TAG = "RecordingService";
    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 4273;
    private static final int SAVED_NOTIFICATION_ID = 4274;
    private static final long RECORDING_NOTIFICATION_RESTORE_DELAY_MS = 1_000L;

    private static final String ACTION_BEGIN = "com.openrecorder.app.action.BEGIN";
    private static final String ACTION_START =
            "com.openrecorder.app.action.START";
    private static final String ACTION_STOP =
            "com.openrecorder.app.action.STOP";
    private static final String ACTION_PAUSE =
            "com.openrecorder.app.action.PAUSE";
    private static final String ACTION_RESUME =
            "com.openrecorder.app.action.RESUME";
    private static final String ACTION_RECORDING_NOTIFICATION_DISMISSED =
            "com.openrecorder.app.action.RECORDING_NOTIFICATION_DISMISSED";
    private static final String ACTION_DELETE =
            "com.openrecorder.app.action.DELETE";

    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_RESULT_DATA = "result_data";
    private static final String EXTRA_AUDIO_SOURCE = "audio_source";
    private static final String EXTRA_SAMPLE_RATE = "sample_rate";
    private static final String EXTRA_VIDEO_RESOLUTION = "video_resolution";
    private static final String EXTRA_VIDEO_FRAME_RATE = "video_frame_rate";
    private static final String EXTRA_VIDEO_BITRATE = "video_bitrate";
    private static final String EXTRA_VIDEO_CODEC = "video_codec";
    private static final String EXTRA_NAMING_PATTERN = "naming_pattern";
    private static final String EXTRA_RECORDING_TIMEOUT_MINUTES = "recording_timeout_minutes";
    private static final String EXTRA_COUNTDOWN_SECONDS = "countdown_seconds";
    private static volatile RecordingCountdown countdown;
    private int countdownSeconds;

    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean recorderStarted = new AtomicBoolean();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorService executor;
    private NotificationManager notificationManager;
    private volatile ScreenRecorder recorder;
    private Runnable pendingNotificationRestore;
    private Runnable preparationCountdownTick;
    private Toast preparationCountdownToast;
    private Runnable recordingTimeout;
    private int recordingTimeoutMinutes;
    private volatile boolean paused;
    private volatile long recordingStartedAtElapsedRealtime;
    private volatile long pausedAtElapsedRealtime;
    private volatile long totalPausedDurationMs;
    private AudioSource audioSource = AudioSource.NONE;

    private static final String EXTRA_ROOT_AUDIO_CAPTURE = "use_root_audio_capture";

    static Intent createStartIntent(
            Context context,
            int resultCode,
            Intent resultData,
            AudioSource audioSource,
            int sampleRate,
            boolean useRootAudioCapture,
            int videoResolution,
            int videoFrameRate,
            int videoBitrate,
            int videoCodec,
            String namingPattern,
            int recordingTimeoutMinutes,
            int countdownSeconds) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_AUDIO_SOURCE, audioSource.ordinal())
                .putExtra(EXTRA_ROOT_AUDIO_CAPTURE, useRootAudioCapture)
                .putExtra(EXTRA_SAMPLE_RATE, RecordingOptions.normalizeSampleRate(sampleRate))
                .putExtra(
                        EXTRA_VIDEO_RESOLUTION,
                        RecordingOptions.normalizeVideoResolution(videoResolution))
                .putExtra(
                        EXTRA_VIDEO_FRAME_RATE,
                        RecordingOptions.normalizeVideoFrameRate(videoFrameRate))
                .putExtra(
                        EXTRA_VIDEO_BITRATE,
                        RecordingOptions.normalizeVideoBitrate(videoBitrate))
                .putExtra(EXTRA_VIDEO_CODEC, RecordingOptions.normalizeVideoCodec(videoCodec))
                .putExtra(
                        EXTRA_NAMING_PATTERN,
                        RecordingOptions.normalizeNamingPattern(namingPattern))
                .putExtra(
                        EXTRA_RECORDING_TIMEOUT_MINUTES,
                        RecordingOptions.normalizeRecordingTimeoutMinutes(recordingTimeoutMinutes))
                .putExtra(EXTRA_COUNTDOWN_SECONDS,
                        RecordingOptions.normalizeCountdownSeconds(countdownSeconds));
    }

    static Intent createBeginIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_BEGIN);
    }

    static long getCountdownDeadline() {
        RecordingCountdown current = countdown;
        return current == null ? 0L : current.deadlineMillis;
    }

    static Intent createStopIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_STOP);
    }

    private static Intent createPauseIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_PAUSE);
    }

    private static Intent createResumeIntent(Context context) {
        return new Intent(context, RecordingService.class).setAction(ACTION_RESUME);
    }

    private static Intent createRecordingNotificationDismissedIntent(Context context) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_RECORDING_NOTIFICATION_DISMISSED);
    }

    private static Intent createDeleteIntent(Context context, Uri uri) {
        return new Intent(context, RecordingService.class)
                .setAction(ACTION_DELETE)
                .setData(uri);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ScreenRecorderWorker");
            return thread;
        });
        executor.execute(() -> ScreenRecorder.deleteStaleTemporaryFiles(this));
        notificationManager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_recording_text));
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_DELETE.equals(intent.getAction())) {
            deleteSavedRecording(intent, startId);
            return START_NOT_STICKY;
        }
        if (finished.get()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        switch (intent.getAction()) {
            case ACTION_START:
                startFromIntent(intent);
                break;
            case ACTION_BEGIN:
                beginRecording();
                break;
            case ACTION_STOP:
                stopAndSave();
                break;
            case ACTION_PAUSE:
                requestPause(startId);
                break;
            case ACTION_RESUME:
                requestResume(startId);
                break;
            case ACTION_RECORDING_NOTIFICATION_DISMISSED:
                scheduleRecordingNotificationRestore();
                if (recorder == null && RecordingState.get() == RecordingState.IDLE) {
                    stopSelf(startId);
                }
                break;
            default:
                stopSelf(startId);
                break;
        }
        return START_NOT_STICKY;
    }

    private void startFromIntent(Intent intent) {
        if (recorder != null || RecordingState.get() != RecordingState.IDLE
                || stopping.get()) {
            return;
        }

        audioSource = AudioSource.fromOrdinal(
                intent.getIntExtra(EXTRA_AUDIO_SOURCE, AudioSource.NONE.ordinal()));
        try {
            startForegroundCompat(createRecordingNotification(true));
            setRecordingState(RecordingState.PRIMING);
            startPreparationIndicator();

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
            } else {
                resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            }
            if (resultCode != Activity.RESULT_OK || resultData == null) {
                throw new IllegalArgumentException("Missing or invalid MediaProjection result");
            }

            int sampleRate = RecordingOptions.normalizeSampleRate(intent.getIntExtra(
                    EXTRA_SAMPLE_RATE,
                    RecordingOptions.DEFAULT_SAMPLE_RATE));
            int videoResolution = RecordingOptions.normalizeVideoResolution(intent.getIntExtra(
                    EXTRA_VIDEO_RESOLUTION,
                    RecordingOptions.DEFAULT_VIDEO_RESOLUTION));
            int videoFrameRate = RecordingOptions.normalizeVideoFrameRate(intent.getIntExtra(
                    EXTRA_VIDEO_FRAME_RATE,
                    RecordingOptions.DEFAULT_VIDEO_FRAME_RATE));
            int videoBitrate = RecordingOptions.normalizeVideoBitrate(intent.getIntExtra(
                    EXTRA_VIDEO_BITRATE,
                    RecordingOptions.DEFAULT_VIDEO_BITRATE));
            int videoCodec = RecordingOptions.normalizeVideoCodec(intent.getIntExtra(
                    EXTRA_VIDEO_CODEC,
                    RecordingOptions.DEFAULT_VIDEO_CODEC));
            String namingPattern = RecordingOptions.normalizeNamingPattern(
                    intent.getStringExtra(EXTRA_NAMING_PATTERN));
            recordingTimeoutMinutes = RecordingOptions.normalizeRecordingTimeoutMinutes(
                    intent.getIntExtra(
                            EXTRA_RECORDING_TIMEOUT_MINUTES,
                            RecordingOptions.DEFAULT_RECORDING_TIMEOUT_MINUTES));
            ScreenRecorder pendingRecorder = new ScreenRecorder(
                    this,
                    resultCode,
                    resultData,
                    audioSource,
                    sampleRate,
                    intent.getBooleanExtra(EXTRA_ROOT_AUDIO_CAPTURE, false),
                    videoResolution,
                    videoFrameRate,
                    videoBitrate,
                    videoCodec,
                    namingPattern,
                    this);
            recorder = pendingRecorder;

            countdownSeconds = RecordingOptions.normalizeCountdownSeconds(
                    intent.getIntExtra(EXTRA_COUNTDOWN_SECONDS, 0));
            executor.execute(() -> prepareRecorder(pendingRecorder));
        } catch (Exception error) {
            failRecording(error, recorder);
        }
    }

    private void prepareRecorder(ScreenRecorder pendingRecorder) {
        if (finished.get() || stopping.get()) {
            pendingRecorder.release();
            finishService();
            return;
        }

        try {
            pendingRecorder.prime();
            if (finished.get() || stopping.get() || recorder != pendingRecorder) {
                pendingRecorder.release();
                finishService();
                return;
            }

            runOnMainThread(() -> {
                if (finished.get() || stopping.get() || recorder != pendingRecorder) return;
                cancelPreparationCountdown();
                setRecordingState(RecordingState.READY);
                notificationManager.notify(NOTIFICATION_ID, createRecordingNotification(true));
            });
        } catch (Exception error) {
            if (stopping.get()) {
                pendingRecorder.release();
                finishService();
            } else {
                failRecording(error, pendingRecorder);
            }
        }
    }

    private void startRecorder(ScreenRecorder pendingRecorder) {
        if (finished.get() || stopping.get()) {
            pendingRecorder.release();
            finishService();
            return;
        }

        try {
            pendingRecorder.start();
            runOnMainThread(this::cancelPreparationCountdown);
            recorderStarted.set(true);
            recordingStartedAtElapsedRealtime = pendingRecorder.getRecordingStartedAtElapsedRealtime();
            pausedAtElapsedRealtime = 0L;
            totalPausedDurationMs = 0L;
            paused = false;
            if (!stopping.get() && !finished.get()) {
                scheduleRecordingTimeout();
                setRecordingState(RecordingState.RECORDING);
                notificationManager.notify(
                        NOTIFICATION_ID,
                        createRecordingNotification(false));
            }
        } catch (Exception error) {
            if (stopping.get()) {
                pendingRecorder.release();
                finishService();
            } else {
                failRecording(error, pendingRecorder);
            }
        }
    }

    private void startForegroundCompat(Notification notification) {
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && audioSource.usesMicrophone()) {
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        startForeground(NOTIFICATION_ID, notification, type);
    }

    private Notification createRecordingNotification(boolean preparing) {
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                2,
                createStopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action stopAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(preparing ? R.string.cancel_recording : R.string.action_stop),
                stopIntent).build();
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_recording_title))
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        if (preparing) {
            int state = RecordingState.get();
            builder.setContentText(getString(state == RecordingState.READY
                            ? R.string.status_ready
                            : state == RecordingState.COUNTDOWN
                            ? R.string.status_starting : R.string.notification_preparing))
                    .setShowWhen(false);
            if (state == RecordingState.READY) {
                PendingIntent begin = PendingIntent.getService(this, 5, createBeginIntent(this),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                builder.addAction(new Notification.Action.Builder(android.R.drawable.ic_media_play,
                        getString(R.string.start_recording), begin).build());
            }
        } else {
            PendingIntent notificationDismissedIntent = PendingIntent.getService(
                    this,
                    4,
                    createRecordingNotificationDismissedIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.setDeleteIntent(notificationDismissedIntent);

            boolean notificationPaused = paused;
            PendingIntent toggleIntent = PendingIntent.getService(
                    this,
                    1,
                    notificationPaused ? createResumeIntent(this) : createPauseIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Action toggleAction = new Notification.Action.Builder(
                    notificationPaused
                            ? android.R.drawable.ic_media_play
                            : android.R.drawable.ic_media_pause,
                    getString(notificationPaused ? R.string.action_resume : R.string.action_pause),
                    toggleIntent).build();

            if (notificationPaused) {
                String elapsed = DateUtils.formatElapsedTime(
                        getActiveRecordingDurationMs() / 1_000L);
                builder.setContentText(getString(R.string.notification_paused_time, elapsed))
                        .setShowWhen(false);
            } else {
                builder.setContentText(getString(R.string.notification_recording_text))
                        .setWhen(System.currentTimeMillis() - getActiveRecordingDurationMs())
                        .setUsesChronometer(true)
                        .setShowWhen(true);
            }
            builder.addAction(toggleAction);
        }

        return builder.addAction(stopAction).build();
    }

    private void scheduleRecordingNotificationRestore() {
        if (!isRecordingNotificationRequired()) {
            return;
        }

        cancelPendingNotificationRestore();
        Runnable restoreNotification = new Runnable() {
            @Override
            public void run() {
                if (pendingNotificationRestore != this) {
                    return;
                }
                pendingNotificationRestore = null;
                if (isRecordingNotificationRequired()) {
                    startForegroundCompat(createRecordingNotification(false));
                }
            }
        };
        pendingNotificationRestore = restoreNotification;
        mainHandler.postDelayed(
                restoreNotification,
                RECORDING_NOTIFICATION_RESTORE_DELAY_MS);
    }

    private boolean isRecordingNotificationRequired() {
        int state = RecordingState.get();
        return recorder != null
                && recorderStarted.get()
                && !stopping.get()
                && !finished.get()
                && (state == RecordingState.RECORDING || state == RecordingState.PAUSED);
    }

    private void cancelPendingNotificationRestore() {
        Runnable restoreNotification = pendingNotificationRestore;
        if (restoreNotification != null) {
            mainHandler.removeCallbacks(restoreNotification);
            pendingNotificationRestore = null;
        }
    }

    private Notification createSavingNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_recording_title))
                .setContentText(getString(R.string.notification_saving))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private Notification createSavedNotification(Uri uri) {
        Intent viewIntent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent viewPendingIntent = PendingIntent.getActivity(
                this,
                10,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent sendIntent = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        sendIntent.setClipData(ClipData.newRawUri("recording", uri));
        Intent chooserIntent = Intent.createChooser(
                sendIntent,
                getString(R.string.share_video))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent sharePendingIntent = PendingIntent.getActivity(
                this,
                11,
                chooserIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action shareAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_share,
                getString(R.string.action_share),
                sharePendingIntent).build();
        PendingIntent deletePendingIntent = PendingIntent.getService(
                this,
                12,
                createDeleteIntent(this, uri),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action deleteAction = new Notification.Action.Builder(
                android.R.drawable.ic_menu_delete,
                getString(R.string.action_delete),
                deletePendingIntent).build();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_saved_title))
                .setContentText(getString(R.string.notification_saved_text))
                .setContentIntent(viewPendingIntent)
                .setAutoCancel(true)
                .addAction(shareAction)
                .addAction(deleteAction)
                .build();
    }

    private Notification createErrorNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recorder)
                .setContentTitle(getString(R.string.notification_error_title))
                .setContentText(getString(R.string.notification_error_text))
                .setAutoCancel(true)
                .build();
    }

    private void requestPause(int startId) {
        if (recorder == null || !recorderStarted.get()) {
            if (RecordingState.get() == RecordingState.IDLE) {
                stopSelf(startId);
            }
            return;
        }
        executor.execute(this::pauseRecording);
    }

    private void requestResume(int startId) {
        if (recorder == null || !recorderStarted.get()) {
            if (RecordingState.get() == RecordingState.IDLE) {
                stopSelf(startId);
            }
            return;
        }
        executor.execute(this::resumeRecording);
    }

    private void pauseRecording() {
        ScreenRecorder activeRecorder = recorder;
        if (activeRecorder == null || paused || stopping.get() || finished.get()) {
            return;
        }

        try {
            activeRecorder.pause();
            if (recorder != activeRecorder || stopping.get() || finished.get()) {
                return;
            }
            pausedAtElapsedRealtime = SystemClock.elapsedRealtime();
            paused = true;
            cancelRecordingTimeout();
            setRecordingState(RecordingState.PAUSED);
            notificationManager.notify(
                    NOTIFICATION_ID,
                    createRecordingNotification(false));
        } catch (Exception error) {
            Log.e(TAG, "Unable to pause recording", error);
            runOnMainThread(() -> Toast.makeText(
                    this,
                    R.string.pause_recording_failed,
                    Toast.LENGTH_LONG).show());
        }
    }

    private void resumeRecording() {
        ScreenRecorder activeRecorder = recorder;
        if (activeRecorder == null || !paused || stopping.get() || finished.get()) {
            return;
        }

        try {
            activeRecorder.resume();
            if (recorder != activeRecorder || stopping.get() || finished.get()) {
                return;
            }
            long resumedAt = SystemClock.elapsedRealtime();
            totalPausedDurationMs += Math.max(0L, resumedAt - pausedAtElapsedRealtime);
            pausedAtElapsedRealtime = 0L;
            paused = false;
            scheduleRecordingTimeout();
            setRecordingState(RecordingState.RECORDING);
            notificationManager.notify(
                    NOTIFICATION_ID,
                    createRecordingNotification(false));
        } catch (Exception error) {
            Log.e(TAG, "Unable to resume recording", error);
            runOnMainThread(() -> Toast.makeText(
                    this,
                    R.string.resume_recording_failed,
                    Toast.LENGTH_LONG).show());
        }
    }

    private long getActiveRecordingDurationMs() {
        long end = paused ? pausedAtElapsedRealtime : SystemClock.elapsedRealtime();
        return RecordingTimeout.activeDurationMillis(recordingStartedAtElapsedRealtime,
                end, totalPausedDurationMs);
    }

    private void scheduleRecordingTimeout() {
        cancelRecordingTimeout();
        long remainingMs = RecordingTimeout.remainingMillis(recordingStartedAtElapsedRealtime,
                getActiveRecordingDurationMs(), recordingTimeoutMinutes);
        if (remainingMs < 0L) return;
        recordingTimeout = this::stopAndSave;
        mainHandler.postDelayed(recordingTimeout, Math.max(0L, remainingMs));
    }

    private void cancelRecordingTimeout() {
        if (recordingTimeout != null) {
            mainHandler.removeCallbacks(recordingTimeout);
            recordingTimeout = null;
        }
    }

    private void deleteSavedRecording(Intent intent, int startId) {
        Uri uri = intent.getData();
        boolean deleted = false;
        if (uri != null) {
            try {
                deleted = getContentResolver().delete(uri, null, null) > 0;
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to delete saved recording", error);
            }
        }

        if (deleted) {
            notificationManager.cancel(SAVED_NOTIFICATION_ID);
            Toast.makeText(this, R.string.recording_deleted, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.recording_delete_failed, Toast.LENGTH_LONG).show();
        }
        if (recorder == null && RecordingState.get() == RecordingState.IDLE) {
            stopSelf(startId);
        }
    }

    private void stopAndSave() {
        if (finished.get() || !stopping.compareAndSet(false, true)) {
            return;
        }
        runOnMainThread(() -> {
            cancelPreparationCountdown();
            cancelRecordingTimeout();
        });

        ScreenRecorder activeRecorder = recorder;
        if (activeRecorder == null) {
            finishService();
            return;
        }
        recorder = null;
        boolean shouldSave = recorderStarted.get();
        if (!shouldSave) {
            activeRecorder.cancelPreparation();
        }
        if (shouldSave) {
            setRecordingState(RecordingState.SAVING);
            notificationManager.notify(NOTIFICATION_ID, createSavingNotification());
        }

        executor.execute(() -> {
            if (finished.get()) {
                return;
            }
            if (!shouldSave) {
                activeRecorder.release();
                finishService();
                return;
            }

            try {
                activeRecorder.stop();
                Uri uri = activeRecorder.save();
                notificationManager.notify(
                        SAVED_NOTIFICATION_ID,
                        createSavedNotification(uri));
                runOnMainThread(() -> Toast.makeText(
                        this,
                        R.string.saved_success,
                        Toast.LENGTH_LONG).show());
            } catch (Exception error) {
                Log.e(TAG, "Unable to stop or save recording", error);
                notificationManager.notify(
                        SAVED_NOTIFICATION_ID,
                        createErrorNotification());
            } finally {
                activeRecorder.release();
                finishService();
            }
        });
    }

    private void failRecording(Exception error, ScreenRecorder failedRecorder) {
        Log.e(TAG, "Unable to start recording", error);
        runOnMainThread(this::cancelPreparationCountdown);
        if (failedRecorder != null) {
            if (recorder == failedRecorder) {
                recorder = null;
            }
            failedRecorder.release();
        }
        int message = containsVideoDaemonUnavailable(error)
                ? R.string.video_daemon_unavailable
                : R.string.recording_failed;
        runOnMainThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        notificationManager.notify(SAVED_NOTIFICATION_ID, createErrorNotification());
        finishService();
    }

    private static boolean containsVideoDaemonUnavailable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof VideoDaemonUnavailableException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void finishService() {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        mainHandler.post(() -> {
            cancelPreparationCountdown();
            cancelRecordingTimeout();
            cancelPendingNotificationRestore();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void runOnMainThread(Runnable runnable) {
        mainHandler.post(runnable);
    }

    private void setRecordingState(int state) {
        RecordingState.set(state);
        RecorderTileService.requestStateRefresh(this);
    }

    @Override
    public void onProjectionStopped() {
        stopAndSave();
    }

    @Override
    public void onRecorderLimitReached() {
        stopAndSave();
    }

    @Override
    public void onRecorderError(Exception error) {
        Log.e(TAG, "Video encoder error", error);
        stopAndSave();
    }

    @Override
    public void onAudioCaptureFailed() {
        if (finished.get() || (stopping.get() && !recorderStarted.get())) {
            return;
        }
        runOnMainThread(() -> Toast.makeText(
                this,
                R.string.audio_capture_failed,
                Toast.LENGTH_LONG).show());
    }

    private void startPreparationIndicator() {
        cancelPreparationCountdown();
        preparationCountdownToast = Toast.makeText(this,
                R.string.preparing_recording, Toast.LENGTH_SHORT);
        preparationCountdownToast.show();
    }

    private void beginRecording() {
        ScreenRecorder preparedRecorder = recorder;
        if (RecordingState.get() != RecordingState.READY || preparedRecorder == null
                || stopping.get() || finished.get() || !preparedRecorder.beginCountdown()) return;
        countdown = new RecordingCountdown(SystemClock.elapsedRealtime(), countdownSeconds);
        setRecordingState(RecordingState.COUNTDOWN);
        notificationManager.notify(NOTIFICATION_ID, createRecordingNotification(true));
        preparationCountdownTick = new Runnable() {
            @Override public void run() {
                if (preparationCountdownTick != this || stopping.get() || finished.get()
                        || recorder != preparedRecorder) return;
                long remaining = countdown.remainingMillis(SystemClock.elapsedRealtime());
                if (remaining <= 0L) {
                    cancelPreparationCountdown();
                    // The receiver preserves a quiet interval after the last toast disappears,
                    // then requests the final keyframe before the shared timeline is created.
                    preparedRecorder.onPreparationOverlayCleared();
                    executor.execute(() -> startRecorder(preparedRecorder));
                    return;
                }
                if (preparationCountdownToast != null) preparationCountdownToast.cancel();
                preparationCountdownToast = Toast.makeText(RecordingService.this,
                        getString(R.string.status_countdown, (remaining + 999L) / 1_000L),
                        Toast.LENGTH_LONG);
                preparationCountdownToast.show();
                // Fewer long text toasts avoid Android's background toast rate limit.
                // The elapsed-time deadline still owns the exact countdown duration.
                mainHandler.postDelayed(this, Math.min(3_000L, remaining));
            }
        };
        preparationCountdownTick.run();
    }

    private void cancelPreparationCountdown() {
        Runnable tick = preparationCountdownTick;
        preparationCountdownTick = null;
        countdown = null;
        if (tick != null) {
            mainHandler.removeCallbacks(tick);
        }
        if (preparationCountdownToast != null) {
            preparationCountdownToast.cancel();
            preparationCountdownToast = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        cancelPreparationCountdown();
        cancelRecordingTimeout();
        cancelPendingNotificationRestore();
        ScreenRecorder activeRecorder = recorder;
        recorder = null;
        if (activeRecorder != null) {
            if (!recorderStarted.get()) activeRecorder.cancelPreparation();
            activeRecorder.release();
        }
        setRecordingState(RecordingState.IDLE);
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
