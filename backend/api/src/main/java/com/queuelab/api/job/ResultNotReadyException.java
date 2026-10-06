package com.queuelab.api.job;

import java.util.UUID;

import com.queuelab.core.job.JobStatus;

/** Se pidió el resultado de un trabajo que no ha terminado bien (aún no, o nunca: {@code FAILED}). */
public class ResultNotReadyException extends RuntimeException {

    public ResultNotReadyException(UUID id, JobStatus status) {
        super(status == JobStatus.FAILED
                ? "El trabajo " + id + " falló: no tiene resultado"
                : "El resultado del trabajo " + id + " todavía no está disponible (estado " + status + ")");
    }
}
