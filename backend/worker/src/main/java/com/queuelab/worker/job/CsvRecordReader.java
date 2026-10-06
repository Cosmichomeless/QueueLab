package com.queuelab.worker.job;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Lector CSV (RFC 4180) en streaming: devuelve un registro cada vez y no guarda nada más, así que la memoria
 * depende del registro actual y no del tamaño del fichero. Es estricto: una comilla en mitad de un campo sin
 * comillas, texto tras la comilla de cierre o comillas sin cerrar son errores, no se "adivinan".
 */
final class CsvRecordReader {

    /** Tope de un campo, para que un fichero sin saltos de línea o con comillas abiertas no agote la memoria. */
    static final int MAX_FIELD_LENGTH = 1024 * 1024;

    private final Reader in;
    private long line = 1;
    private long recordLine;
    private int peeked = -2;

    CsvRecordReader(Reader in) {
        this.in = in;
    }

    /** Línea (desde 1) en la que empieza el último registro devuelto. */
    long recordLine() {
        return recordLine;
    }

    /** @return los campos del siguiente registro, o {@code null} al terminar el fichero */
    List<String> next() throws IOException {
        int c = read();
        if (c == -1) {
            return null;
        }
        recordLine = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        while (true) {
            if (quoted) {
                if (c == -1) {
                    throw new CsvFormatException(recordLine, "comillas sin cerrar");
                }
                if (c == '"') {
                    if (peek() == '"') {
                        read();
                        append(field, '"');
                    } else {
                        quoted = false;
                        closedQuote = true;
                    }
                } else {
                    append(field, (char) c);
                }
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                closedQuote = false;
            } else if (c == -1 || c == '\n' || c == '\r') {
                if (c == '\r' && peek() == '\n') {
                    read();
                }
                fields.add(field.toString());
                return fields;
            } else if (closedQuote) {
                throw new CsvFormatException(line, "texto después de cerrar las comillas");
            } else if (c == '"') {
                if (field.length() > 0) {
                    throw new CsvFormatException(line, "comillas dentro de un campo sin comillas");
                }
                quoted = true;
            } else {
                append(field, (char) c);
            }
            c = read();
        }
    }

    private void append(StringBuilder field, char c) {
        if (field.length() >= MAX_FIELD_LENGTH) {
            throw new CsvFormatException(line, "un campo supera el máximo de " + MAX_FIELD_LENGTH + " caracteres");
        }
        field.append(c);
    }

    private int peek() throws IOException {
        if (peeked == -2) {
            peeked = in.read();
        }
        return peeked;
    }

    private int read() throws IOException {
        int c;
        if (peeked != -2) {
            c = peeked;
            peeked = -2;
        } else {
            c = in.read();
        }
        if (c == '\n') {
            line++;
        } else if (c == '\r' && peek() != '\n') {
            line++;
        }
        return c;
    }
}
