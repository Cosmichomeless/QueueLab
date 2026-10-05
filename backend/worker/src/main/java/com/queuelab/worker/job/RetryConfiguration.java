package com.queuelab.worker.job;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RetryConfiguration {

    @Bean
    RetryPolicy retryPolicy(@Value("${queuelab.worker.retry.max-attempts:3}") int maxAttempts,
            @Value("${queuelab.worker.retry.initial-delay:5s}") Duration initialDelay,
            @Value("${queuelab.worker.retry.multiplier:2.0}") double multiplier,
            @Value("${queuelab.worker.retry.max-delay:5m}") Duration maxDelay) {
        return new RetryPolicy(maxAttempts, initialDelay, multiplier, maxDelay);
    }
}
