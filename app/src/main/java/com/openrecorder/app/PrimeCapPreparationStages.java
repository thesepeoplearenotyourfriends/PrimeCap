package com.openrecorder.app;

/** Monotonic, stage-driven preparation state used by the video receiver thread. */
final class PrimeCapPreparationStages {
    enum Action { NONE, REQUEST_READINESS_SYNC, START_COUNTDOWN, REQUEST_FINAL_SYNC, COMPLETE }

    private enum Stage { MINIMUM_WARMUP, READINESS_KEYFRAME, COUNTDOWN, QUIET, FINAL_KEYFRAME, COMPLETE }

    private final long minimumWarmupNanos;
    private final long retryNanos;
    private final long quietNanos;
    private Stage stage = Stage.MINIMUM_WARMUP;
    private long startedNanos;
    private long readinessRequestNanos;
    private long countdownStartedNanos;
    private long countdownEndedNanos;
    private long quietUntilNanos;
    private long finalRequestNanos;

    PrimeCapPreparationStages(long minimumWarmupNanos, long retryNanos, long quietNanos) {
        this.minimumWarmupNanos = minimumWarmupNanos;
        this.retryNanos = retryNanos;
        this.quietNanos = quietNanos;
    }

    synchronized void start(long nowNanos) {
        startedNanos = nowNanos;
    }

    synchronized Action onSample(long nowNanos, boolean keyFrame) {
        if (stage == Stage.MINIMUM_WARMUP && nowNanos - startedNanos >= minimumWarmupNanos) {
            stage = Stage.READINESS_KEYFRAME;
            readinessRequestNanos = nowNanos;
            return Action.REQUEST_READINESS_SYNC;
        }
        if (stage == Stage.READINESS_KEYFRAME) {
            if (keyFrame && nowNanos >= readinessRequestNanos) {
                stage = Stage.COUNTDOWN;
                countdownStartedNanos = nowNanos;
                return Action.START_COUNTDOWN;
            }
            if (nowNanos - readinessRequestNanos >= retryNanos) {
                readinessRequestNanos = nowNanos;
                return Action.REQUEST_READINESS_SYNC;
            }
        } else if (stage == Stage.QUIET && nowNanos >= quietUntilNanos) {
            stage = Stage.FINAL_KEYFRAME;
            finalRequestNanos = nowNanos;
            return Action.REQUEST_FINAL_SYNC;
        } else if (stage == Stage.FINAL_KEYFRAME
                && keyFrame && nowNanos >= finalRequestNanos) {
            stage = Stage.COMPLETE;
            return Action.COMPLETE;
        }
        return Action.NONE;
    }

    synchronized boolean onCountdownCleared(long nowNanos) {
        if (stage != Stage.COUNTDOWN) {
            return false;
        }
        countdownEndedNanos = nowNanos;
        quietUntilNanos = nowNanos + quietNanos;
        stage = Stage.QUIET;
        return true;
    }

    synchronized long getReadinessRequestNanos() { return readinessRequestNanos; }
    synchronized long getCountdownStartedNanos() { return countdownStartedNanos; }
    synchronized long getCountdownEndedNanos() { return countdownEndedNanos; }
    synchronized long getFinalRequestNanos() { return finalRequestNanos; }
}
