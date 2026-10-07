package com.openrecorder.app;

/** No timeout is armed until actual recording starts; preparation and pauses are excluded. */
final class RecordingTimeout {
    static long activeDurationMillis(long startedAt, long end, long pausedDuration) {
        return startedAt <= 0L ? 0L : Math.max(0L, end - startedAt - pausedDuration);
    }

    static long remainingMillis(long startedAt, long activeDuration, int minutes) {
        if (startedAt <= 0L || minutes == RecordingOptions.RECORDING_TIMEOUT_OFF) return -1L;
        return Math.max(0L, minutes * 60_000L - activeDuration);
    }

    private RecordingTimeout() {}
}
