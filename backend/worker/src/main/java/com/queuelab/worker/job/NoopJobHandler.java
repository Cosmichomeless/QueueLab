package com.queuelab.worker.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;

/** Trabajo de prueba: no hace nada útil, solo deja constancia de que el worker lo recibió. */
@Component
class NoopJobHandler implements JobHandler {

    static final String TYPE = "noop";

    private static final Logger log = LoggerFactory.getLogger(NoopJobHandler.class);

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String handle(Job job) {
        log.info("Ejecutando trabajo {} de tipo '{}'", job.id(), job.type());
        return "Trabajo de tipo '" + job.type() + "' completado";
    }
}
