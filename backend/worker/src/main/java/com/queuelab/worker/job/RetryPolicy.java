package com.queuelab.worker.job;

import java.time.Duration;

/**
 * Política de reintentos: como mucho {@code maxAttempts} ejecuciones en total (la primera incluida) y,
 * entre una y otra, una espera que crece de forma exponencial hasta {@code maxDelay}.
 *
 * <p>La espera tras el intento <i>n</i> es {@code initialDelay × multiplier^(n-1)}, acotada por
 * {@code maxDelay}. No lleva jitter: con un solo trabajo por mensaje y la entrega repartida por el
 * dispatcher, no hay tormenta de reintentos que evitar.
 */
public record RetryPolicy(int maxAttempts, Duration initialDelay, double multiplier, Duration maxDelay) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("max-attempts debe ser al menos 1");
        }
        if (initialDelay.isNegative() || maxDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("las esperas no pueden ser negativas y max-delay >= initial-delay");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier debe ser al menos 1");
        }
    }

    /** ¿Quedan intentos tras haber consumido {@code attemptsMade}? */
    public boolean hasAttemptsLeft(int attemptsMade) {
        return attemptsMade < maxAttempts;
    }

    /** Espera antes del siguiente intento, habiendo fallado el número {@code attemptsMade} (empieza en 1). */
    public Duration delayAfter(int attemptsMade) {
        double millis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, attemptsMade - 1));
        return millis >= maxDelay.toMillis() ? maxDelay : Duration.ofMillis((long) millis);
    }
}
