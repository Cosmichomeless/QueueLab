package com.queuelab.api.job;

/** El fichero subido supera el tamaño máximo configurado; se rechaza antes de guardar nada. */
public class UploadTooLargeException extends RuntimeException {

    public UploadTooLargeException(long maxBytes) {
        super("El fichero supera el tamaño máximo permitido de " + CsvUploadService.describe(maxBytes));
    }
}
