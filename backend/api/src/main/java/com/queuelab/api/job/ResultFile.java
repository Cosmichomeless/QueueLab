package com.queuelab.api.job;

/**
 * Fichero de resultado de un trabajo terminado, tal y como lo anuncia la API.
 *
 * @param name        nombre sugerido para guardarlo
 * @param contentType tipo de contenido de la descarga
 * @param size        tamaño en bytes
 * @param downloadUrl ruta (relativa al servidor) desde la que descargarlo
 */
public record ResultFile(String name, String contentType, long size, String downloadUrl) {
}
