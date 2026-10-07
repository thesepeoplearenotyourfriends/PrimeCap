package com.openrecorder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PrimeCapPreparationStagesTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    public void lateReadinessKeyframeStartsIndependentLaterStages() {
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(
                20 * SECOND, 2 * SECOND, SECOND);
        stages.start(0L);

        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(20 * SECOND, false));
        // Simulate retries and a readiness keyframe arriving near the old 85-second deadline.
        for (long time = 22 * SECOND; time <= 82 * SECOND; time += 2 * SECOND) {
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
        for (long time : new long[]{25 * SECOND, 3600 * SECOND, 86400 * SECOND}) {
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
    public void cancelPrimingOrReadyCannotProduceRecordingBoundary() {
        PrimeCapPreparationStages priming = new PrimeCapPreparationStages(20 * SECOND, SECOND, SECOND);
        priming.start(0L);
        assertFalse(priming.beginCountdown(SECOND));
        for (PrimeCapPreparationStages stages : new PrimeCapPreparationStages[]{priming, readyStages()}) {
            stages.cancel();
            assertFalse(stages.beginCountdown(100 * SECOND));
            assertFalse(stages.onCountdownCleared(100 * SECOND));
            assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(200 * SECOND, true));
        }
    }

    private PrimeCapPreparationStages readyStages() {
        PrimeCapPreparationStages stages = new PrimeCapPreparationStages(20 * SECOND, 2 * SECOND, SECOND);
        stages.start(0L);
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_READINESS_SYNC,
                stages.onSample(20 * SECOND, false));
        assertEquals(PrimeCapPreparationStages.Action.READY, stages.onSample(21 * SECOND, true));
        return stages;
    }
}
