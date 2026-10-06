package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.worker.job.JobProcessor;
import com.queuelab.worker.support.ContainersTestConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Un CSV real guardado en disco, un trabajo real en PostgreSQL y el procesador del worker de por medio. */
@SpringBootTest(properties = "queuelab.worker.retry.max-attempts=3")
@Import(ContainersTestConfiguration.class)
class CsvImportTest {

    @Autowired
    JobProcessor processor;

    @Autowired
    JobRepository jobs;

    @Autowired
    FileStorage storage;

    @Autowired
    JdbcClient jdbc;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    private Job csvJob(byte[] content) {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);
        var stored = storage.store(StorageArea.INPUT, job.id() + ".csv", new ByteArrayInputStream(content));
        jobs.attachInput(job.id(), stored.reference());
        return job;
    }

    private Job csvJob(String content) {
        return csvJob(content.getBytes(StandardCharsets.UTF_8));
    }

    private Job run(Job job) {
        processor.process(JobMessage.forJob(job.id()));
        return jobs.findById(job.id()).orElseThrow();
    }

    private JsonNode stats(Job job) throws IOException {
        String ref = jobs.findResultRef(job.id()).orElseThrow();
        try (var in = storage.open(ref)) {
            return json.readTree(in);
        }
    }

    @Test
    void sampleCsvEndsCompletedWithTheExpectedStatistics() throws IOException {
        Job job = csvJob("""
                id,city,price
                1,Madrid,10.5
                2,Sevilla,
                3,"Vigo, Galicia",4
                """);

        Job done = run(job);

        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("CSV procesado: 3 filas, 3 columnas");
        assertThat(done.error()).isNull();
        assertThat(jobs.findResultRef(job.id())).contains("results/" + job.id() + ".stats.json");

        JsonNode stats = stats(job);
        assertThat(stats.get("rows").asLong()).isEqualTo(3);
        JsonNode id = stats.get("columns").get(0);
        assertThat(id.get("name").asString()).isEqualTo("id");
        assertThat(id.get("type").asString()).isEqualTo("number");
        assertThat(id.get("nonEmpty").asLong()).isEqualTo(3);
        assertThat(id.get("empty").asLong()).isZero();
        assertThat(id.get("min").decimalValue()).isEqualByComparingTo("1");
        assertThat(id.get("max").decimalValue()).isEqualByComparingTo("3");
        assertThat(id.get("sum").decimalValue()).isEqualByComparingTo("6");
        assertThat(id.get("mean").decimalValue()).isEqualByComparingTo("2");

        JsonNode city = stats.get("columns").get(1);
        assertThat(city.get("type").asString()).isEqualTo("text");
        assertThat(city.get("minLength").asInt()).isEqualTo(6);
        assertThat(city.get("maxLength").asInt()).isEqualTo(13);

        JsonNode price = stats.get("columns").get(2);
        assertThat(price.get("type").asString()).isEqualTo("number");
        assertThat(price.get("nonEmpty").asLong()).isEqualTo(2);
        assertThat(price.get("empty").asLong()).isEqualTo(1);
        assertThat(price.get("min").decimalValue()).isEqualByComparingTo("4");
        assertThat(price.get("max").decimalValue()).isEqualByComparingTo("10.5");
        assertThat(price.get("sum").decimalValue()).isEqualByComparingTo("14.5");
        assertThat(price.get("mean").decimalValue()).isEqualByComparingTo("7.25");
    }

    @Test
    void numbersAreWrittenInPlainNotationAndAMixedColumnIsText() throws IOException {
        Job job = csvJob("n,mixed\n1000000000,1\n3,abc\n");

        run(job);

        String raw = new String(storage.open(jobs.findResultRef(job.id()).orElseThrow()).readAllBytes(),
                StandardCharsets.UTF_8);
        assertThat(raw).contains("\"sum\":1000000003").doesNotContain("E+").doesNotContain("E9");
        assertThat(stats(job).get("columns").get(1).get("type").asString()).isEqualTo("text");
    }

    @Test
    void headerOnlyFileIsValidWithZeroRows() throws IOException {
        Job done = run(csvJob("a,b\n"));

        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("CSV procesado: 0 filas, 2 columnas");
        JsonNode column = stats(done).get("columns").get(0);
        assertThat(column.get("type").asString()).isEqualTo("text");
        assertThat(column.get("nonEmpty").asLong()).isZero();
        assertThat(column.has("minLength")).isFalse();
    }

    @Test
    void bomCrlfAndTrailingBlankLinesAreTolerated() {
        Job done = run(csvJob("﻿a,b\r\n1,2\r\n3,4\r\n\r\n\r\n"));

        assertThat(done.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(done.result()).isEqualTo("CSV procesado: 2 filas, 2 columnas");
    }

    @Test
    void blankLinesInTheMiddleOfASingleColumnFileAreEmptyValues() throws IOException {
        Job job = run(csvJob("a\n1\n\n3\n"));

        assertThat(job.result()).isEqualTo("CSV procesado: 3 filas, 1 columnas");
        assertThat(stats(job).get("columns").get(0).get("empty").asLong()).isEqualTo(1);
    }

    @Test
    void rowWithTheWrongNumberOfFieldsFailsWithoutRetriesAndWithoutLeakingData() {
        Job job = csvJob("a,b\n1,2\nSECRETO,9,9\n");

        Job failed = run(job);

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.error()).isEqualTo("Línea 3: tiene 3 campos y la cabecera 2");
        assertThat(failed.error()).doesNotContain("SECRETO");
        assertThat(jobs.findResultRef(job.id())).isEmpty();
    }

    @Test
    void unclosedQuotesFailWithoutRetries() {
        Job failed = run(csvJob("a,b\n1,\"sin cerrar\n"));

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.error()).isEqualTo("Línea 2: comillas sin cerrar");
    }

    @Test
    void invalidHeadersFailWithAClearMessageAndWithoutRetries() {
        assertFailsWith("", "El fichero está vacío: falta la cabecera");
        assertFailsWith("a,,c\n1,2,3\n", "Cabecera: la columna 2 no tiene nombre");
        assertFailsWith("id,Name,NAME\n1,2,3\n", "Cabecera: la columna 3 está duplicada");
        assertFailsWith("a," + "x".repeat(101) + "\n", "Cabecera: el nombre de la columna 2 supera los 100 caracteres");
        assertFailsWith(String.join(",", java.util.Collections.nCopies(101, "c")) + "\n", "Cabecera: hay más de 100 columnas");
    }

    private void assertFailsWith(String csv, String message) {
        Job failed = run(csvJob(csv));
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.error()).isEqualTo(message);
    }

    @Test
    void invalidUtf8FailsWithoutRetries() {
        Job failed = run(csvJob(new byte[] {'a', ',', 'b', '\n', (byte) 0xC3, (byte) 0x28, ',', '1', '\n'}));

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(failed.error()).isEqualTo("El fichero no es UTF-8 válido");
    }

    @Test
    void missingInputFileIsTransientAndGetsRetried() {
        Job job = csvJob("a\n1\n");
        storage.delete(jobs.findInputRef(job.id()).orElseThrow());

        Job retrying = run(job);

        assertThat(retrying.status()).isEqualTo(JobStatus.RETRYING);
        assertThat(retrying.attempts()).isEqualTo(1);
        assertThat(retrying.error()).isEqualTo("No se encuentra el fichero de entrada");
    }

    @Test
    void jobWithoutInputFileFailsWithoutRetries() {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);

        Job failed = run(job);

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.error()).isEqualTo("El trabajo no tiene fichero de entrada");
    }

    @Test
    void aRetryOverwritesItsStatisticsInsteadOfDuplicatingThem() throws IOException {
        Job job = csvJob("a\n1\n");
        run(job);
        String ref = jobs.findResultRef(job.id()).orElseThrow();
        long size = storage.size(ref);

        jdbc.sql("UPDATE jobs SET status = 'QUEUED', finished_at = NULL WHERE id = :id").param("id", job.id()).update();
        Job again = run(job);

        assertThat(again.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(jobs.findResultRef(job.id())).contains(ref);
        assertThat(storage.size(ref)).isEqualTo(size);
    }

    @Test
    void aLargeFileIsProcessedWithCorrectStatistics() throws IOException {
        var csv = new StringBuilder("n,label\n");
        int rows = 200_000;
        for (int i = 1; i <= rows; i++) {
            csv.append(i).append(",fila ").append(i).append('\n');
        }

        Job job = run(csvJob(csv.toString()));

        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.result()).isEqualTo("CSV procesado: 200000 filas, 2 columnas");
        JsonNode n = stats(job).get("columns").get(0);
        assertThat(n.get("sum").decimalValue()).isEqualByComparingTo("20000100000");
        assertThat(n.get("mean").decimalValue()).isEqualByComparingTo("100000.5");
    }
}
