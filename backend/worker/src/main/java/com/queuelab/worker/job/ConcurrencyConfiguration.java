package com.queuelab.worker.job;

import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Límite de concurrencia del consumidor. El número de consumidores es <b>fijo</b> ({@code concurrentConsumers ==
 * maxConcurrentConsumers}): el contenedor no escala por su cuenta, así que el máximo de trabajos simultáneos por
 * proceso es exactamente el configurado, y el total del sistema es ese valor por el número de workers.
 */
@Configuration(proxyBeanMethods = false)
class ConcurrencyConfiguration {

    @Bean
    ConsumerConcurrency consumerConcurrency(@Value("${queuelab.worker.concurrency:2}") int concurrency,
            @Value("${queuelab.worker.prefetch:1}") int prefetch) {
        return new ConsumerConcurrency(concurrency, prefetch);
    }

    @Bean
    ContainerCustomizer<SimpleMessageListenerContainer> consumerLimits(ConsumerConcurrency limits) {
        return container -> {
            container.setConcurrentConsumers(limits.consumers());
            container.setMaxConcurrentConsumers(limits.consumers());
            container.setPrefetchCount(limits.prefetch());
        };
    }
}
