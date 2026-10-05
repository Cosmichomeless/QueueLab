package com.queuelab.worker.job;

import com.queuelab.core.job.Job;

/** Ejecuta el trabajo ya cargado de PostgreSQL. */
public interface JobExecutor {

    void execute(Job job);
}
