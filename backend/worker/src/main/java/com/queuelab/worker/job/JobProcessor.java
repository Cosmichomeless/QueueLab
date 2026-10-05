package com.queuelab.worker.job;

import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.messaging.JobMessage;

/** Carga el trabajo del mensaje desde PostgreSQL (la fuente de verdad) y lo ejecuta. */
@Component
public class JobProcessor {

    private final JobRepository jobs;
    private final JobExecutor executor;

    JobProcessor(JobRepository jobs, JobExecutor executor) {
        this.jobs = jobs;
        this.executor = executor;
    }

    /** @throws UnknownJobException si el mensaje apunta a un trabajo que no existe */
    public void process(JobMessage message) {
        Job job = jobs.findById(message.jobId()).orElseThrow(() -> new UnknownJobException(message.jobId()));
        executor.execute(job);
    }
}
