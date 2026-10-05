package com.queuelab.worker.job;

import com.queuelab.core.job.Job;

/** Ejecuta el trabajo ya cargado de PostgreSQL y en estado {@code RUNNING}. */
public interface JobExecutor {

    /**
     * @return resumen del resultado, que se guarda en el trabajo
     * @throws JobExecutionException si el trabajo falla de forma esperada (su mensaje se guarda como error)
     */
    String execute(Job job);
}
