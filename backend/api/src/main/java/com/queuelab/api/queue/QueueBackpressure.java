package com.queuelab.api.queue;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.queuelab.core.job.JobRepository;

/**
 * Política de contrapresión: la API deja de aceptar trabajo nuevo cuando ya hay demasiado esperando.
 *
 * <p>La medida de saturación es el número de trabajos pendientes ({@code QUEUED} o {@code RETRYING}, es decir,
 * aceptados y todavía sin worker). Los {@code RUNNING} no cuentan: ya tienen capacidad asignada. Al alcanzar
 * {@code queuelab.backpressure.max-pending} los envíos nuevos se rechazan con {@code 503} y {@code Retry-After}
 * ({@link QueueSaturatedException}) en lugar de dejar crecer la cola sin límite.
 *
 * <p>El umbral es blando: con envíos simultáneos el contador puede rebasarse en unos pocos trabajos, lo que no
 * importa para proteger la cola. Las respuestas idempotentes repetidas no encolan nada y no se rechazan.
 */
@Service
public class QueueBackpressure {

    private final JobRepository jobs;
    private final int maxPending;
    private final Duration retryAfter;

    public QueueBackpressure(JobRepository jobs,
            @Value("${queuelab.backpressure.max-pending:1000}") int maxPending,
            @Value("${queuelab.backpressure.retry-after:30s}") Duration retryAfter) {
        if (maxPending < 1) {
            throw new IllegalArgumentException(
                    "queuelab.backpressure.max-pending debe ser al menos 1 (valor: " + maxPending + ")");
        }
        if (retryAfter.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException(
                    "queuelab.backpressure.retry-after debe ser al menos 1 s (valor: " + retryAfter + ")");
        }
        this.jobs = jobs;
        this.maxPending = maxPending;
        this.retryAfter = retryAfter;
    }

    /** Estado actual de la cola frente al umbral. */
    public QueueStatus status() {
        long pending = jobs.countWaiting(maxPending);
        return new QueueStatus(pending, maxPending, pending >= maxPending, retryAfter.toSeconds());
    }

    /** Falla con {@link QueueSaturatedException} si la cola está saturada; si no, no hace nada. */
    public void ensureCapacity() {
        QueueStatus status = status();
        if (status.saturated()) {
            throw new QueueSaturatedException(status);
        }
    }
}
