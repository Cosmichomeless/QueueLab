package com.queuelab.core.messaging;

import java.util.UUID;

/**
 * Mensaje que acaba en la cola dead-letter cuando un trabajo agota sus reintentos. Es un
 * {@link JobMessage} ampliado con lo necesario para diagnosticarlo sin abrir la base de datos.
 *
 * <p>Nunca lleva datos de entrada del trabajo: solo su id, cuántas veces se ejecutó y el mismo resumen
 * de error que ya expone la API.
 *
 * <pre>{"version":1,"jobId":"2f6c0d52-…","attempts":3,"cause":"El servicio externo no responde"}</pre>
 *
 * @param attempts ejecuciones realizadas, la primera incluida
 * @param cause    resumen del último error, seguro de mostrar
 */
public record DeadLetterMessage(int version, UUID jobId, int attempts, String cause) {

    public DeadLetterMessage {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId es obligatorio");
        }
    }

    public static DeadLetterMessage forJob(UUID jobId, int attempts, String cause) {
        return new DeadLetterMessage(JobMessage.CURRENT_VERSION, jobId, attempts, cause);
    }
}
