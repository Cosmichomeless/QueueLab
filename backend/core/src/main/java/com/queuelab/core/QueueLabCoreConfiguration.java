package com.queuelab.core;

import java.nio.file.Path;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.core.job.JobRepository;
import com.queuelab.core.outbox.OutboxRepository;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.LocalFileStorage;
import com.queuelab.core.tracing.JobTracing;

import io.opentelemetry.api.OpenTelemetry;

/**
 * Beans compartidos. Cada proceso que acceda a la base de datos la importa con
 * {@code @Import(QueueLabCoreConfiguration.class)}.
 */
@Configuration(proxyBeanMethods = false)
public class QueueLabCoreConfiguration {

    @Bean
    JobRepository jobRepository(JdbcClient jdbcClient) {
        return new JobRepository(jdbcClient);
    }

    @Bean
    OutboxRepository outboxRepository(JdbcClient jdbcClient) {
        return new OutboxRepository(jdbcClient);
    }

    /** Spans de los trabajos; sin OpenTelemetry configurado no registran nada. */
    @Bean
    JobTracing jobTracing(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new JobTracing(openTelemetry.getIfAvailable(OpenTelemetry::noop));
    }

    /**
     * Almacenamiento local. La API (entradas) y el worker (lectura y resultados) deben apuntar al mismo
     * directorio; por defecto es relativo al directorio de trabajo del proceso, así que en despliegues reales
     * conviene fijarlo con {@code QUEUELAB_STORAGE_DIRECTORY}.
     */
    @Bean
    FileStorage fileStorage(@Value("${queuelab.storage.directory:./data/storage}") Path directory) {
        return new LocalFileStorage(directory);
    }
}
