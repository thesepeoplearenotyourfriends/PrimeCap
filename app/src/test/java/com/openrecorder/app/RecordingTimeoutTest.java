package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingTimeoutTest {
    @Test public void preparationReadyAndCountdownDoNotConsumeOrArmTimeout() {
        assertEquals(0L, RecordingTimeout.activeDurationMillis(0, 3_600_000, 0));
        assertEquals(-1L, RecordingTimeout.remainingMillis(0, 0, 1));
        long actualStart = 3_600_000;
        assertEquals(60_000, RecordingTimeout.remainingMillis(actualStart,
                RecordingTimeout.activeDurationMillis(actualStart, actualStart, 0), 1));
        assertEquals(0, RecordingTimeout.remainingMillis(actualStart, 60_000, 1));
    }
    @Test public void pausedTimeIsExcludedAndOffNeverArms() {
        assertEquals(30_000, RecordingTimeout.activeDurationMillis(1000, 91_000, 60_000));
        assertEquals(30_000, RecordingTimeout.remainingMillis(1000, 30_000, 1));
        assertEquals(-1, RecordingTimeout.remainingMillis(1000, 30_000, 0));
    }
    @Test public void delayedCountdownTickDoesNotChangeDeadline() {
        RecordingCountdown countdown = new RecordingCountdown(1000, 10);
        assertEquals(11_000, countdown.deadlineMillis);
        assertEquals(1000, countdown.remainingMillis(10_000));
        assertEquals(0, countdown.remainingMillis(20_000));
    }
}
