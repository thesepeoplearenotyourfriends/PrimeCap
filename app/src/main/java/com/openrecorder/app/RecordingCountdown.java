package com.openrecorder.app;

/** Service-owned elapsed-time deadline; delayed UI ticks cannot extend the countdown. */
final class RecordingCountdown {
    final long deadlineMillis;

    RecordingCountdown(long nowMillis, int seconds) {
        deadlineMillis = nowMillis + RecordingOptions.normalizeCountdownSeconds(seconds) * 1_000L;
    }

    long remainingMillis(long nowMillis) { return Math.max(0L, deadlineMillis - nowMillis); }
}
