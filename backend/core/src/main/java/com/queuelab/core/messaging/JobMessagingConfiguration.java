package com.queuelab.core.messaging;

import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra la topología de RabbitMQ. La importan la API y el worker con
 * {@code @Import(JobMessagingConfiguration.class)}; el {@code RabbitAdmin} de Spring Boot declara
 * exchange, colas y bindings al abrir la primera conexión y en cada reconexión (idempotente).
 */
@Configuration(proxyBeanMethods = false)
public class JobMessagingConfiguration {

    @Bean
    Declarables jobMessagingTopology() {
        return JobMessagingTopology.declarables();
    }
}
