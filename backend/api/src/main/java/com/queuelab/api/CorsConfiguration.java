package com.queuelab.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Deja que el dashboard (otro origen, el navegador) llame a la API. Solo se admiten los orígenes de
 * {@code queuelab.cors.allowed-origins} (lista separada por comas; vacía = sin CORS) y solo en {@code /api/**}.
 */
@Configuration
class CorsConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    CorsConfiguration(@Value("${queuelab.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "Idempotency-Key")
                .exposedHeaders("Location", "Content-Disposition");
    }
}
