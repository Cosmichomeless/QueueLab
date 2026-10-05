package com.queuelab.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;

/** Trabajo de prueba: no hace nada útil, solo deja constancia de que el worker lo recibió. */
@Component
class NoopJobExecutor implements JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(NoopJobExecutor.class);

    @Override
    public String execute(Job job) {
        log.info("Ejecutando trabajo {} de tipo '{}'", job.id(), job.type());
        return "Trabajo de tipo '" + job.type() + "' completado";
    }
}
