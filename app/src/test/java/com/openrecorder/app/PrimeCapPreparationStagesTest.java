package com.openrecorder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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
        assertEquals(PrimeCapPreparationStages.Action.START_COUNTDOWN,
                stages.onSample(84 * SECOND, true));
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
        assertEquals(PrimeCapPreparationStages.Action.START_COUNTDOWN,
                stages.onSample(121, true));
        assertTrue(stages.onCountdownCleared(126));
        assertEquals(PrimeCapPreparationStages.Action.NONE, stages.onSample(135, true));
        assertEquals(PrimeCapPreparationStages.Action.REQUEST_FINAL_SYNC,
                stages.onSample(136, true));
        assertEquals(PrimeCapPreparationStages.Action.COMPLETE,
                stages.onSample(137, true));
    }
}
