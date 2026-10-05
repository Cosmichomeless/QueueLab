package com.queuelab.worker.job;

import java.time.Duration;

/**
 * Cuánto dura el lease de un trabajo en ejecución y cada cuánto se renueva. El worker lo renueva
 * mientras sigue vivo, así que {@code duration} solo marca cuánto tarda en detectarse una caída: debe
 * ser bastante mayor que {@code duration / 3} (el intervalo de renovación) para tolerar un latido perdido.
 */
public record LeasePolicy(Duration duration) {

    public LeasePolicy {
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("lease.duration debe ser positiva");
        }
    }

    /** Un tercio del lease: caben dos latidos fallidos antes de que venza. */
    public Duration renewInterval() {
        return duration.dividedBy(3);
    }
}
