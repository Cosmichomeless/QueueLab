package com.queuelab.worker.job;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;

/**
 * Único {@link JobExecutor} del worker: delega en el {@link JobHandler} del tipo del trabajo. Un tipo sin
 * manejador es un fallo permanente (reintentar no lo arregla), no un error inesperado.
 */
@Component
class TypeRoutingJobExecutor implements JobExecutor {

    private final Map<String, JobHandler> handlers = new HashMap<>();

    TypeRoutingJobExecutor(List<JobHandler> handlers) {
        for (JobHandler handler : handlers) {
            if (this.handlers.put(handler.type(), handler) != null) {
                throw new IllegalStateException("Hay dos manejadores para el tipo '" + handler.type() + "'");
            }
        }
    }

    @Override
    public String execute(Job job) {
        JobHandler handler = handlers.get(job.type());
        if (handler == null) {
            throw new JobExecutionException("El worker no sabe ejecutar trabajos de tipo '" + job.type() + "'");
        }
        return handler.handle(job);
    }
}
