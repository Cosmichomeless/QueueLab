package com.queuelab.worker.job;

/**
 * Fallo esperado de un trabajo. Su mensaje es un resumen pensado para mostrarse en la API
 * ({@code error} del trabajo), así que no debe contener datos sensibles ni trazas. Cualquier otra
 * excepción se guarda con un texto genérico y el detalle solo va al log del worker.
 */
public class JobExecutionException extends RuntimeException {

    public JobExecutionException(String summary) {
        super(summary);
    }

    public JobExecutionException(String summary, Throwable cause) {
        super(summary, cause);
    }
}
