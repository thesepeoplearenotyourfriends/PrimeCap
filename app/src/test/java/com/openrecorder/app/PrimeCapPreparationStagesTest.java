package com.openrecorder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PrimeCapPreparationStagesTest {
    private static final long SECOND = 1_000_000_000L;
    private static final long GUARD = PrimeCapPreparationStages.MINIMUM_PRIMING_SECONDS * SECOND;

    @Test
    public void lateReadinessKeyframeStartsIndependentLaterStages() {
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(
                GUARD, 2 * SECOND, SECOND);
        stages.start(0L);

        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(GUARD, false));
        // Simulate retries and a readiness keyframe arriving near the old 85-second deadline.
        for (long time = 27 * SECOND; time <= 83 * SECOND; time += 2 * SECOND) {
            assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                    stages.onSample(time, false));
        }
        assertEquals(PrimeCapPreparationStages.Action.READY,
                stages.onSample(84 * SECOND, true));
        assertTrue(stages.beginCountdown(84 * SECOND));
        assertTrue(stages.onCountdownCleared(89 * SECOND));

        assertEquals(PrimeCapPreparationStages.Action.NONE,
                stages.onSample(89 * SECOND + 900_000_000L, true));
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC,
                stages.onSample(90 * SECOND, false));
        assertEquals(PrimeCapPreparationStages.Action.COMPLETE,
                stages.onSample(90 * SECOND + 100_000_000L, true));
    }

    @Test
    public void keyframeBeforeFinalRequestIsNeverAccepted() {
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(20, 2, 10);
        stages.start(100);
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(120, true));
        assertEquals(PrimeCapPreparationStages.Action.READY,
                stages.onSample(121, true));
        assertTrue(stages.beginCountdown(121));
        assertTrue(stages.onCountdownCleared(126));
        assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(135, true));
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC,
                stages.onSample(136, true));
        assertEquals(PrimeCapPreparationStages.Action.COMPLETE,
                stages.onSample(137, true));
    }
    @Test
    public void readyDiscardsKeyframesIndefinitelyWithoutCreatingTimeline() {
        PrimeCapPreparationStages stages = readyStages();
        for (long time : new long[]{GUARD + SECOND, 3600 * SECOND, 86400 * SECOND}) {
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(time, true));
        }
        assertEquals(0L, stages.getCountdownStartedNanos());
        assertEquals(0L, stages.getFinalRequestNanos());
        assertFalse(stages.onCountdownCleared(86400 * SECOND));
    }

    @Test
    public void configuredCountdownAndQuietIntervalPrecedeSharedTimelineBoundary() {
        for (int seconds : new int[]{0, 3, 5, 10}) {
            PrimeCapPreparationStages stages = readyStages();
            long startMillis = 3_600_000L; // User waited READY for an hour.
            RecordingCountdown countdown = new RecordingCountdown(startMillis, seconds);
            assertTrue(stages.beginCountdown(startMillis * 1_000_000L));
            assertFalse(stages.beginCountdown(startMillis * 1_000_000L));
            assertEquals(seconds * 1000L, countdown.remainingMillis(startMillis));
            long clearedNanos = countdown.deadlineMillis * 1_000_000L;
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(clearedNanos, true));
            assertTrue(stages.onCountdownCleared(clearedNanos));
            assertFalse(stages.onCountdownCleared(clearedNanos));
            assertEquals(PrimeCapPreparationStages.Action.NONE,
                    stages.onSample(clearedNanos + SECOND - 1, true));
            assertEquals(PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC,
                    stages.onSample(clearedNanos + SECOND, true));
            long boundaryNanos = clearedNanos + SECOND + 100_000_000L;
            assertEquals(PrimeCapPreparationStages.Action.COMPLETE,
                    stages.onSample(boundaryNanos, true));
            RecordingTimeline timeline = new RecordingTimeline(boundaryNanos);
            assertFalse(timeline.shouldInclude(clearedNanos));
            assertEquals(0L, timeline.toPresentationTimeUs(boundaryNanos));
            assertEquals(1_000_000L, timeline.toPresentationTimeUs(boundaryNanos + SECOND));
        }
    }

    @Test
    public void cancelPrimingReadyOrCountdownCannotProduceRecordingBoundary() {
        PrimeCapPreparationStages priming = new PrimeCapPreparationStages(GUARD, SECOND, SECOND);
        priming.start(0L);
        assertFalse(priming.beginCountdown(SECOND));
        PrimeCapPreparationStages countingDown = readyStages();
        assertTrue(countingDown.beginCountdown(GUARD + SECOND));
        for (PrimeCapPreparationStages stages : new PrimeCapPreparationStages[]{
                priming, readyStages(), countingDown}) {
            stages.cancel();
            assertFalse(stages.beginCountdown(100 * SECOND));
            assertFalse(stages.onCountdownCleared(100 * SECOND));
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(200 * SECOND, true));
        }
    }

    @Test
    public void earlyKeyframesCannotBypassFullEmpiricalGuard() {
        assertEquals(25, PrimeCapVideoRecorder.MINIMUM_WARMUP_SECONDS);
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(GUARD, 2 * SECOND, SECOND);
        long started = 123 * SECOND; // Guard is relative to format start, not process uptime.
        stages.start(started);
        for (long offset : new long[]{0, 20 * SECOND, 21 * SECOND, 24 * SECOND, GUARD - 1}) {
            assertEquals(PrimeCapPreparationStages.Action.NONE,
                    stages.onSample(started + offset, true));
            assertFalse(stages.beginCountdown(started + offset));
            assertEquals(0L, stages.getReadinessReachedNanos());
        }
        // Even a keyframe at the guard expiry first requests readiness synchronization.
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(started + GUARD, true));
        assertFalse(stages.beginCountdown(started + GUARD));
        assertEquals(PrimeCapPreparationStages.Action.READY,
                stages.onSample(started + GUARD + 1, true));
        assertTrue(stages.getReadinessReachedNanos() - started >= 25 * SECOND);
    }

    @Test
    public void immediateStartIsSafeForEveryCountdownIncludingZero() {
        for (int seconds : new int[]{0, 3, 5, 10}) {
            PrimeCapPreparationStages stages = new PrimeCapPreparationStages(GUARD, 2 * SECOND, SECOND);
            stages.start(0L);
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(GUARD - 1, true));
            assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                    stages.onSample(GUARD, true));
            long ready = GUARD + 1_000_000L;
            assertEquals(PrimeCapPreparationStages.Action.READY, stages.onSample(ready, true));
            assertEquals(ready, stages.getReadinessReachedNanos());
            RecordingCountdown countdown = new RecordingCountdown(ready / 1_000_000L, seconds);
            assertTrue(stages.beginCountdown(ready));
            // Readiness is already safe before any user-facing countdown time elapses.
            assertTrue(ready > 25 * SECOND);
            assertEquals(seconds * 1_000L, countdown.remainingMillis(ready / 1_000_000L));
            long cleared = countdown.deadlineMillis * 1_000_000L;
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(cleared, true));
            assertTrue(stages.onCountdownCleared(cleared));
            assertEquals(PrimeCapPreparationStages.Action.NONE,
                    stages.onSample(cleared + SECOND - 1, true));
            assertEquals(PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC,
                    stages.onSample(cleared + SECOND, true));
            long boundary = cleared + SECOND + 1;
            assertEquals(PrimeCapPreparationStages.Action.COMPLETE, stages.onSample(boundary, true));
            assertTrue(boundary > 26 * SECOND);
            assertEquals(seconds * SECOND + SECOND + 1, boundary - ready);
        }
    }

    private PrimeCapPreparationStages readyStages() {
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(GUARD, 2 * SECOND, SECOND);
        stages.start(0L);
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(GUARD, false));
        assertEquals(PrimeCapPreparationStages.Action.READY, stages.onSample(GUARD + SECOND, true));
        return stages;
    }
}
