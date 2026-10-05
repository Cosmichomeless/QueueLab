package com.queuelab.core;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.core.job.JobRepository;
import com.queuelab.core.outbox.OutboxRepository;

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
}
