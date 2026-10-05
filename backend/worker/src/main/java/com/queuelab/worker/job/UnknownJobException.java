package com.queuelab.worker.job;

import java.util.UUID;

/** El mensaje es válido pero el trabajo al que apunta no existe: reintentar no lo arreglaría. */
public class UnknownJobException extends RuntimeException {

    public UnknownJobException(UUID jobId) {
        super("No existe el trabajo " + jobId);
    }
}
