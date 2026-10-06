package com.queuelab.worker.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvRecordReaderTest {

    private static List<List<String>> read(String csv) throws IOException {
        var reader = new CsvRecordReader(new StringReader(csv));
        List<List<String>> records = new ArrayList<>();
        List<String> record;
        while ((record = reader.next()) != null) {
            records.add(record);
        }
        return records;
    }

    @Test
    void readsPlainRecordsWithLfCrlfAndCr() throws IOException {
        assertThat(read("a,b\n1,2\r\n3,4\r5,6")).containsExactly(
                List.of("a", "b"), List.of("1", "2"), List.of("3", "4"), List.of("5", "6"));
    }

    @Test
    void readsQuotedFieldsWithCommasEscapedQuotesAndNewlines() throws IOException {
        assertThat(read("a,b\n\"x,y\",\"di \"\"hola\"\"\"\n\"dos\nlíneas\",\"\"\n")).containsExactly(
                List.of("a", "b"), List.of("x,y", "di \"hola\""), List.of("dos\nlíneas", ""));
    }

    @Test
    void keepsEmptyFieldsAndBlankLines() throws IOException {
        assertThat(read("a,b,c\n,,\n\n")).containsExactly(
                List.of("a", "b", "c"), List.of("", "", ""), List.of(""));
    }

    @Test
    void emptyInputHasNoRecords() throws IOException {
        assertThat(read("")).isEmpty();
    }

    @Test
    void reportsTheLineWhereTheRecordStarts() throws IOException {
        var reader = new CsvRecordReader(new StringReader("a\n\"x\ny\"\r\nz\n"));
        reader.next();
        assertThat(reader.recordLine()).isEqualTo(1);
        reader.next();
        assertThat(reader.recordLine()).isEqualTo(2);
        reader.next();
        assertThat(reader.recordLine()).isEqualTo(4);
    }

    @Test
    void unclosedQuotesFailWithTheStartLine() {
        assertThatThrownBy(() -> read("a,b\n1,\"sin cerrar\n3,4\n"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessage("Línea 2: comillas sin cerrar");
    }

    @Test
    void quoteInsideAnUnquotedFieldFails() {
        assertThatThrownBy(() -> read("a\nab\"c\n"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessage("Línea 2: comillas dentro de un campo sin comillas");
    }

    @Test
    void textAfterTheClosingQuoteFails() {
        assertThatThrownBy(() -> read("a\n\"ab\"c\n"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessage("Línea 2: texto después de cerrar las comillas");
    }

    @Test
    void aFieldOverTheLimitFailsInsteadOfExhaustingMemory() {
        String huge = "x".repeat(CsvRecordReader.MAX_FIELD_LENGTH + 1);
        assertThatThrownBy(() -> read("a\n" + huge + "\n"))
                .isInstanceOf(CsvFormatException.class)
                .hasMessageStartingWith("Línea 2: un campo supera el máximo");
    }

    @Test
    void streamsMillionsOfRecordsWithoutKeepingThem() throws IOException {
        var reader = new CsvRecordReader(new GeneratedRows(3_000_000));
        long count = 0;
        while (reader.next() != null) {
            count++;
        }
        assertThat(count).isEqualTo(3_000_001); // cabecera + filas
    }

    /** Genera {@code id,nombre,valor} fila a fila: nunca existe el fichero entero, ni en disco ni en memoria. */
    private static final class GeneratedRows extends Reader {
        private final long rows;
        private long next = -1;
        private String current = "";
        private int pos;

        GeneratedRows(long rows) {
            this.rows = rows;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            int written = 0;
            while (written < length) {
                if (pos == current.length()) {
                    if (next >= rows) {
                        return written == 0 ? -1 : written;
                    }
                    current = next < 0 ? "id,nombre,valor\n" : next + ",\"fila " + next + "\"," + (next * 3) + "\n";
                    next++;
                    pos = 0;
                }
                buffer[offset + written++] = current.charAt(pos++);
            }
            return written;
        }

        @Override
        public void close() {
        }
    }
}
