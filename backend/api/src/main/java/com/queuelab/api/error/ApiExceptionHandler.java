package com.queuelab.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.queuelab.api.job.IdempotencyConflictException;
import com.queuelab.api.job.InvalidRequestException;
import com.queuelab.api.job.JobNotFoundException;
import com.queuelab.api.job.JobNotRetryableException;
import com.queuelab.api.job.UnsupportedUploadTypeException;
import com.queuelab.api.job.UploadTooLargeException;

/**
 * Formato único de errores de la API: {@code application/problem+json} (RFC 9457).
 * Las excepciones propias de Spring MVC (ruta inexistente, método no permitido…) heredan el mismo
 * formato. Ninguna respuesta incluye trazas ni datos internos: el detalle técnico solo va al log.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail jobNotFound(JobNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Trabajo no encontrado");
        return problem;
    }

    @ExceptionHandler(JobNotRetryableException.class)
    ProblemDetail jobNotRetryable(JobNotRetryableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("El trabajo no se puede reintentar");
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflicto de idempotencia");
        return problem;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ProblemDetail invalidRequest(InvalidRequestException ex) {
        return badRequest(ex.getMessage());
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ProblemDetail uploadTooLarge(UploadTooLargeException ex) {
        return payloadTooLarge(ex.getMessage());
    }

    @ExceptionHandler(UnsupportedUploadTypeException.class)
    ProblemDetail unsupportedUpload(UnsupportedUploadTypeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        problem.setTitle("Tipo de fichero no admitido");
        return problem;
    }

    /** El contenedor corta la subida al superar el límite de multipart, antes de que llegue al servicio. */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = payloadTooLarge("El fichero supera el tamaño máximo permitido");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.CONTENT_TOO_LARGE, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestPart(MissingServletRequestPartException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = badRequest("Falta la parte '" + ex.getRequestPartName() + "' de la petición");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** Cuerpo ausente, JSON mal formado o con tipos incorrectos. No se refleja el mensaje de Jackson. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = badRequest("El cuerpo de la petición falta o no es un JSON válido");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Parámetro o variable de ruta con formato incorrecto (id que no es UUID, estado desconocido…). */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : ex.getPropertyName();
        ProblemDetail problem = badRequest("El valor del parámetro '" + name + "' no tiene un formato válido");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /** Red de seguridad: cualquier fallo no previsto es un 500 genérico; el detalle queda solo en el log. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Error no controlado", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Se produjo un error interno. Inténtalo de nuevo más tarde.");
        problem.setTitle("Error interno");
        return problem;
    }

    private static ProblemDetail badRequest(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Petición no válida");
        return problem;
    }

    private static ProblemDetail payloadTooLarge(String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONTENT_TOO_LARGE, detail);
        problem.setTitle("Fichero demasiado grande");
        return problem;
    }
}
