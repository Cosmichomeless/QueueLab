package com.queuelab.worker.job;

/**
 * Fallo esperado pero <b>transitorio</b> (un servicio externo no responde, un recurso está ocupado):
 * el trabajo se reintenta con espera exponencial hasta agotar los intentos. Un
 * {@link JobExecutionException} corriente es permanente y no se reintenta.
 */
public class TransientJobException extends JobExecutionException {

    public TransientJobException(String summary) {
        super(summary);
    }

    public TransientJobException(String summary, Throwable cause) {
        super(summary, cause);
    }
}
