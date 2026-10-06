package com.queuelab.api.job;

/** El fichero subido no se declara como CSV (ni por {@code Content-Type} ni por extensión). */
public class UnsupportedUploadTypeException extends RuntimeException {

    public UnsupportedUploadTypeException() {
        super("Solo se admiten ficheros CSV (Content-Type text/csv o extensión .csv)");
    }
}
