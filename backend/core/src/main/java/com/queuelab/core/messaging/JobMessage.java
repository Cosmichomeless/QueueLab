package com.queuelab.core.messaging;

import java.util.UUID;

/**
 * Mensaje que la API publica y el worker consume cuando un trabajo está listo para procesarse.
 * Solo lleva el id: el worker carga el resto de datos de PostgreSQL, que es la fuente de verdad.
 *
 * <p>Formato en el cable ({@code application/json}):
 * <pre>{"version":1,"jobId":"2f6c0d52-6f0e-4a29-9d3a-1f2b7a7b3f10"}</pre>
 *
 * @param version versión del contrato; cambia solo si el formato deja de ser compatible
 */
public record JobMessage(int version, UUID jobId) {

    /** Versión actual del contrato. */
    public static final int CURRENT_VERSION = 1;

    public JobMessage {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId es obligatorio");
        }
    }

    public static JobMessage forJob(UUID jobId) {
        return new JobMessage(CURRENT_VERSION, jobId);
    }
}
