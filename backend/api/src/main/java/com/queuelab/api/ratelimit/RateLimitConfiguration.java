package com.queuelab.api.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registra la cuota de envíos solo en las rutas que crean trabajos. */
@Configuration
@ConditionalOnProperty(name = "queuelab.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitConfiguration implements WebMvcConfigurer {

    private final SubmissionRateLimiter limiter;

    RateLimitConfiguration(SubmissionRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter)).addPathPatterns("/api/v1/jobs", "/api/v1/jobs/csv");
    }
}
