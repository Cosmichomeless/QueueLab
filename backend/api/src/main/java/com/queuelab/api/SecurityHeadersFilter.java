package com.queuelab.api;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Añade {@code X-Content-Type-Options: nosniff} a todas las respuestas. La API sirve ficheros derivados de lo que
 * sube el cliente (el resultado de un {@code csv-import}) y un navegador que adivinara el tipo podría interpretar
 * como página lo que es JSON. Se pone antes de la cadena para que también lo lleven los errores.
 *
 * <p>La API no usa cookies ni sesión, así que no hay nada más que proteger a este nivel; el resto de la revisión
 * está en {@code docs/security/upload-api-review.md}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
