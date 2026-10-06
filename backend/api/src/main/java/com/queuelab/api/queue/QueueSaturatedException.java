package com.queuelab.api.queue;

/** La cola alcanzó el umbral de contrapresión: el envío se rechaza sin guardar nada. */
public class QueueSaturatedException extends RuntimeException {

    private final transient QueueStatus status;

    public QueueSaturatedException(QueueStatus status) {
        super("La cola está saturada (" + status.pending() + " trabajos pendientes de un máximo de "
                + status.maxPending() + "). Inténtalo de nuevo en " + status.retryAfterSeconds() + " s.");
        this.status = status;
    }

    public QueueStatus status() {
        return status;
    }
}
