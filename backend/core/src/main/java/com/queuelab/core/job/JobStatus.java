package com.queuelab.core.job;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados del ciclo de vida de un {@link Job}.
 *
 * <pre>
 * QUEUED → RUNNING → COMPLETED
 *    ↑        ↓ ↑
 *    |     RETRYING      RUNNING → FAILED
 *    └──────────────────────────────┘ (reintento manual)
 * </pre>
 *
 * {@code COMPLETED} y {@code FAILED} son terminales: ningún worker los toca. La única salida es el
 * reintento manual de un {@code FAILED}, que lo devuelve a {@code QUEUED} ({@link Job#requeued}).
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
            case FAILED -> EnumSet.of(QUEUED);
            case COMPLETED -> EnumSet.noneOf(JobStatus.class);
        };
    }

    public boolean canTransitionTo(JobStatus target) {
        return allowedTargets().contains(target);
    }

    /** Estado final del procesamiento automático (no implica que no pueda reintentarse a mano). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
