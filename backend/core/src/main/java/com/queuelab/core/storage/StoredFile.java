package com.queuelab.core.storage;

/**
 * Fichero guardado. {@code reference} es la única pista que se persiste en la base de datos: es opaca para
 * quien la recibe y solo {@link FileStorage} sabe resolverla.
 */
public record StoredFile(String reference, long size) {
}
