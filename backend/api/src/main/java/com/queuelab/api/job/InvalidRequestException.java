package com.queuelab.api.job;

/** La petición es incorrecta; se responde 400 con el mensaje, que es seguro mostrar al cliente. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
