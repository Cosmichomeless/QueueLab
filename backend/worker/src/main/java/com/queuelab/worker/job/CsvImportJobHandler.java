package com.queuelab.worker.job;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.core.storage.StorageException;
import com.queuelab.core.storage.StoredFileNotFoundException;

import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Procesa un CSV subido (contrato en {@code docs/csv-workload.md}): lo lee en streaming, valida la cabecera
 * y cada fila, y guarda las estadísticas por columna en {@code results/<jobId>.stats.json}. La memoria es
 * O(columnas): el fichero nunca se carga entero.
 */
@Component
class CsvImportJobHandler implements JobHandler {

    static final String TYPE = "csv-import";
    static final int MAX_COLUMNS = 100;
    static final int MAX_HEADER_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(CsvImportJobHandler.class);

    private final JobRepository jobs;
    private final FileStorage storage;
    private final JsonMapper json = JsonMapper.builder().enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).build();

    CsvImportJobHandler(JobRepository jobs, FileStorage storage) {
        this.jobs = jobs;
        this.storage = storage;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String handle(Job job) {
        String inputRef = jobs.findInputRef(job.id())
                .orElseThrow(() -> new JobExecutionException("El trabajo no tiene fichero de entrada"));

        long rows;
        List<ColumnStats> columns;
        try (InputStream in = storage.open(inputRef); Reader reader = utf8(in)) {
            var parsed = parse(reader);
            rows = parsed.rows();
            columns = parsed.columns();
        } catch (StoredFileNotFoundException e) {
            throw new TransientJobException("No se encuentra el fichero de entrada", e);
        } catch (CharacterCodingException e) {
            throw new CsvFormatException("El fichero no es UTF-8 válido");
        } catch (IOException | StorageException e) {
            throw new TransientJobException("No se pudo leer el fichero de entrada", e);
        }

        String resultRef = writeStats(job, rows, columns);
        jobs.attachResult(job.id(), resultRef);
        log.info("Trabajo {}: CSV procesado, {} filas y {} columnas", job.id(), rows, columns.size());
        return "CSV procesado: " + rows + " filas, " + columns.size() + " columnas";
    }

    private record Parsed(long rows, List<ColumnStats> columns) {
    }

    private Parsed parse(Reader reader) throws IOException {
        var csv = new CsvRecordReader(reader);
        List<String> header = csv.next();
        if (header == null) {
            throw new CsvFormatException("El fichero está vacío: falta la cabecera");
        }
        List<ColumnStats> columns = validateHeader(header);

        long rows = 0;
        long pendingBlank = 0; // líneas vacías aún sin decidir si son basura final o filas
        long firstBlankLine = 0;
        List<String> fields;
        while ((fields = csv.next()) != null) {
            if (fields.size() == 1 && fields.get(0).isEmpty()) {
                if (pendingBlank++ == 0) {
                    firstBlankLine = csv.recordLine();
                }
                continue;
            }
            for (long i = 0; i < pendingBlank; i++) {
                addRow(columns, List.of(""), firstBlankLine + i);
                rows++;
            }
            pendingBlank = 0;
            addRow(columns, fields, csv.recordLine());
            rows++;
        }
        return new Parsed(rows, columns);
    }

    private static List<ColumnStats> validateHeader(List<String> header) {
        if (header.size() > MAX_COLUMNS) {
            throw new CsvFormatException("Cabecera: hay más de " + MAX_COLUMNS + " columnas");
        }
        Set<String> seen = new HashSet<>();
        List<ColumnStats> columns = new ArrayList<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (i == 0 && name.startsWith("﻿")) {
                name = name.substring(1);
            }
            if (name.isBlank()) {
                throw new CsvFormatException("Cabecera: la columna " + (i + 1) + " no tiene nombre");
            }
            if (name.codePointCount(0, name.length()) > MAX_HEADER_LENGTH) {
                throw new CsvFormatException("Cabecera: el nombre de la columna " + (i + 1)
                        + " supera los " + MAX_HEADER_LENGTH + " caracteres");
            }
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new CsvFormatException("Cabecera: la columna " + (i + 1) + " está duplicada");
            }
            columns.add(new ColumnStats(name));
        }
        return columns;
    }

    private static void addRow(List<ColumnStats> columns, List<String> fields, long line) {
        if (fields.size() != columns.size()) {
            throw new CsvFormatException(line, "tiene " + fields.size() + " campos y la cabecera "
                    + columns.size());
        }
        for (int i = 0; i < fields.size(); i++) {
            columns.get(i).add(fields.get(i));
        }
    }

    private String writeStats(Job job, long rows, List<ColumnStats> columns) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("rows", rows);
        stats.put("columns", columns.stream().map(ColumnStats::toMap).toList());
        byte[] bytes = json.writeValueAsBytes(stats);
        try {
            return storage.store(StorageArea.RESULT, job.id() + ".stats.json", new ByteArrayInputStream(bytes))
                    .reference();
        } catch (StorageException e) {
            throw new TransientJobException("No se pudo guardar el fichero de estadísticas", e);
        }
    }

    /** UTF-8 estricto (los bytes inválidos son un error, no un {@code ?}) y sin BOM. */
    private static Reader utf8(InputStream in) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new BufferedReader(new BomSkippingReader(new InputStreamReader(in, decoder)));
    }
}
