package com.queuelab.worker.job;

/**
 * El CSV no cumple el contrato. Es un fallo permanente: reintentar no cambia el fichero. El mensaje solo
 * lleva el número de línea y el motivo, nunca el contenido de las filas.
 */
class CsvFormatException extends JobExecutionException {

    CsvFormatException(String message) {
        super(message);
    }

    CsvFormatException(long line, String reason) {
        super("Línea " + line + ": " + reason);
    }
}
