package com.queuelab.core.storage;

import java.io.InputStream;

/**
 * Frontera de almacenamiento de ficheros (entradas y resultados). La API y el worker dependen de esta
 * interfaz, no de dónde viven los bytes: hoy {@link LocalFileStorage}, mañana cualquier almacén de objetos.
 * La base de datos solo guarda la {@linkplain StoredFile#reference() referencia}, nunca el contenido.
 */
public interface FileStorage {

    /**
     * Guarda el contenido bajo {@code name} en la zona indicada, reemplazando lo que hubiera con ese nombre
     * (un reintento reescribe su resultado). Es todo o nada: si falla a medias no queda ningún fichero.
     *
     * @param name nombre elegido por el sistema ({@link StorageNames#isValid}); nunca el del cliente
     * @throws InvalidStorageReferenceException si el nombre no es válido
     * @throws StorageException                 si falla la escritura
     */
    StoredFile store(StorageArea area, String name, InputStream content);

    /**
     * Abre el fichero para leerlo; quien llama debe cerrar el flujo.
     *
     * @throws InvalidStorageReferenceException si la referencia no es válida
     * @throws StoredFileNotFoundException      si no existe
     */
    InputStream open(String reference);

    /** Tamaño en bytes. */
    long size(String reference);

    boolean exists(String reference);

    /** Borra el fichero; devuelve {@code false} si no existía. */
    boolean delete(String reference);
}
