package com.queuelab.core.storage;

/** Fallo de E/S del almacenamiento (disco lleno, permisos...). Es transitorio: reintentar puede funcionar. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
