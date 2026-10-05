package com.queuelab.core.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados del ciclo de vida de un {@link Job}.
 *
 * <pre>
 * QUEUED → RUNNING → COMPLETED
 *             ↓ ↑
 *          RETRYING      RUNNING → FAILED
 * </pre>
 *
 * {@code COMPLETED} y {@code FAILED} son terminales.
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    RETRYING;

    /** Estados a los que se puede pasar desde este. */
    public Set<JobStatus> allowedTargets() {
        return switch (this) {
            case QUEUED -> EnumSet.of(RUNNING);
            case RUNNING -> EnumSet.of(COMPLETED, FAILED, RETRYING);
            case RETRYING -> EnumSet.of(RUNNING);
            case COMPLETED, FAILED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus target) {
        return allowedTargets().contains(target);
    }

    public boolean isTerminal() {
        return allowedTargets().isEmpty();
    }
}
