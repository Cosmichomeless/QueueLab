package com.queuelab.api;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Deja que el dashboard (otro origen, el navegador) llame a la API. Solo se admiten los orígenes de
 * {@code queuelab.cors.allowed-origins} (lista separada por comas; vacía = sin CORS) y solo en {@code /api/**}.
 *
 * <p>Es un filtro y no un {@code WebMvcConfigurer} a propósito: así también cubre las respuestas que MVC genera
 * antes de resolver el handler (p. ej. el 413 de un multipart demasiado grande); sin las cabeceras CORS el
 * navegador las vería como un fallo de red y no podría mostrar el motivo.
 */
@Configuration
class CorsConfig {

    @Bean
    CorsFilter corsFilter(@Value("${queuelab.cors.allowed-origins:}") String[] allowedOrigins) {
        var source = new UrlBasedCorsConfigurationSource();
        if (allowedOrigins.length > 0) {
            var cors = new CorsConfiguration();
            cors.setAllowedOrigins(List.of(allowedOrigins));
            cors.setAllowedMethods(List.of("GET", "POST"));
            cors.setAllowedHeaders(List.of("Content-Type", "Idempotency-Key"));
            cors.setExposedHeaders(List.of("Location", "Content-Disposition", "Retry-After", "RateLimit-Limit",
                    "RateLimit-Remaining", "RateLimit-Reset"));
            source.registerCorsConfiguration("/api/**", cors);
        }
        return new CorsFilter(source);
    }
}
