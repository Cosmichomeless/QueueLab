package com.queuelab.core.messaging;

/**
 * El cuerpo recibido no cumple el contrato. Reintentarlo no lo arregla: el consumidor debe
 * descartarlo (a la cola de mensajes muertos), no devolverlo a la cola.
 */
public class MalformedJobMessageException extends RuntimeException {

    public MalformedJobMessageException(String message) {
        super(message);
    }

    public MalformedJobMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
