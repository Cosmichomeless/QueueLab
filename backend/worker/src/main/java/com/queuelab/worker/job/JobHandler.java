package com.queuelab.worker.job;

import com.queuelab.core.job.Job;

/** Sabe ejecutar un tipo de trabajo. {@link TypeRoutingJobExecutor} elige el manejador por {@link #type()}. */
interface JobHandler {

    /** Tipo de trabajo que atiende (el mismo valor que {@code job.type()} y que {@code queuelab.jobs.types}). */
    String type();

    /** Mismo contrato que {@link JobExecutor#execute}. */
    String handle(Job job);
}
