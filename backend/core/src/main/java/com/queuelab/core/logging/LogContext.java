package com.queuelab.core.logging;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Contexto de log compartido por API y worker: el id del trabajo y un <b>id de correlación</b> que viaja con él
 * desde la petición HTTP hasta la ejecución en el worker (cabecera HTTP → fila del outbox → cabecera AMQP →
 * worker). Ambos van en el MDC, así que aparecen en cada línea de log como campos propios.
 *
 * <p>El id de correlación solo identifica; nunca debe llevar datos del cliente. Un valor recibido de fuera
 * solo se acepta si tiene el formato de {@link #isValid}: así no se puede inyectar saltos de línea ni
 * contenido arbitrario en los logs.
 */
public final class LogContext {

    /** Clave del MDC (y nombre del campo en el log estructurado) del id de correlación. */
    public static final String CORRELATION_ID = "correlationId";

    /** Clave del MDC del id del trabajo. */
    public static final String JOB_ID = "jobId";

    /** Cabecera HTTP por la que el cliente puede aportar el id y por la que la API lo devuelve. */
    public static final String HTTP_HEADER = "X-Correlation-Id";

    /** Cabecera del mensaje AMQP que lleva el id hasta el worker. */
    public static final String AMQP_HEADER = "x-correlation-id";

    /** Longitud máxima, igual que la columna {@code outbox_events.correlation_id}. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    private LogContext() {
    }

    /** Un id nuevo, para cuando la petición no trae uno válido. */
    public static String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /** {@code true} si el valor es un id aceptable: 1–64 caracteres entre letras, dígitos, {@code . _ -}. */
    public static boolean isValid(String value) {
        return value != null && VALID.matcher(value).matches();
    }

    /** El id de correlación del hilo actual, o {@code null} si no hay. */
    public static String correlationId() {
        return MDC.get(CORRELATION_ID);
    }

    /**
     * Pone el contexto indicado en el hilo actual (un valor {@code null} no se toca) y lo restaura al cerrar,
     * de modo que un hilo reutilizado (p. ej. un consumidor) no arrastre el contexto del mensaje anterior.
     * Úsalo con {@code try}-con-recursos.
     */
    public static Scope with(String correlationId, UUID jobId) {
        Scope scope = new Scope(MDC.get(CORRELATION_ID), MDC.get(JOB_ID));
        if (correlationId != null) {
            MDC.put(CORRELATION_ID, correlationId);
        }
        if (jobId != null) {
            MDC.put(JOB_ID, jobId.toString());
        }
        return scope;
    }

    /** Valores anteriores del MDC, que se recuperan al cerrar. */
    public static final class Scope implements AutoCloseable {

        private final String correlationId;
        private final String jobId;

        private Scope(String correlationId, String jobId) {
            this.correlationId = correlationId;
            this.jobId = jobId;
        }

        @Override
        public void close() {
            restore(CORRELATION_ID, correlationId);
            restore(JOB_ID, jobId);
        }

        private static void restore(String key, String previous) {
            if (previous == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, previous);
            }
        }
    }
}
