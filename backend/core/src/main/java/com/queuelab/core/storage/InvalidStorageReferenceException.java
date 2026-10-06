package com.queuelab.core.storage;

/** Nombre o referencia que no cumple el formato, o que intenta salir del directorio configurado. */
public class InvalidStorageReferenceException extends IllegalArgumentException {

    public InvalidStorageReferenceException(String message) {
        super(message);
    }
}
