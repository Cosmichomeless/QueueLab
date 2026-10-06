package com.queuelab.api.logging;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.queuelab.core.logging.LogContext;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Da a cada petición un <b>id de correlación</b>: el de la cabecera {@code X-Correlation-Id} si trae uno válido
 * (ver {@link LogContext#isValid}) o uno nuevo. Queda en el MDC mientras dure la petición, se devuelve en la
 * misma cabecera de la respuesta (también en los errores) y viaja con el trabajo hasta el worker.
 *
 * <p>Va el primero de la cadena para que todo lo que se loguee después —límite de envíos, errores,
 * servicios— lleve el id. Un valor inválido no se rechaza ni se copia al log: simplemente se sustituye.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(LogContext.HTTP_HEADER);
        String id = LogContext.isValid(supplied) ? supplied : LogContext.newCorrelationId();
        response.setHeader(LogContext.HTTP_HEADER, id);
        try (LogContext.Scope ignored = LogContext.with(id, null)) {
            chain.doFilter(request, response);
        }
    }
}
