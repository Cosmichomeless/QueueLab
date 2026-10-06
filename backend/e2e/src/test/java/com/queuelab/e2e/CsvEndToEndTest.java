package com.queuelab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subida, procesamiento y descarga de un CSV con los jars reales de la API y del worker como procesos aparte.
 *
 * <p>Los casos finos (cada formato de CSV, cada error de fila) ya los cubren los tests de módulo; aquí se
 * comprueba que las piezas encajan de verdad: multipart por HTTP, almacenamiento compartido en disco, cola,
 * worker, resultado en streaming y limpieza. El límite de subida y la retención se acortan por entorno para
 * poder ejercitarlos en segundos.
 */
class CsvEndToEndTest {

    static final long MAX_FILE_BYTES = 2 * 1024 * 1024;

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String SAMPLE = """
            id,city,price
            1,Madrid,10.5
            2,Sevilla,
            3,"Vigo, Galicia",4
            """;

    static AppProcess api;
    static AppProcess worker;
    static String apiUrl;
    static Path storage;

    private static final String DASHBOARD_ORIGIN = "http://localhost:3000";

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        RABBIT.start();

        int apiPort = freePort();
        apiUrl = "http://localhost:" + apiPort;
        storage = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().resolve("target/e2e-storage");
        Map<String, String> env = Map.of(
                "QUEUELAB_DB_URL", POSTGRES.getJdbcUrl(),
                "QUEUELAB_DB_USER", POSTGRES.getUsername(),
                "QUEUELAB_DB_PASSWORD", POSTGRES.getPassword(),
                "QUEUELAB_RABBITMQ_HOST", "localhost",
                "QUEUELAB_RABBITMQ_PORT", String.valueOf(RABBIT.getAmqpPort()),
                "QUEUELAB_RABBITMQ_USER", RABBIT.getAdminUsername(),
                "QUEUELAB_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());

        api = AppProcess.start("api", merge(env, Map.of(
                "SERVER_PORT", String.valueOf(apiPort),
                "QUEUELAB_CSV_MAX_FILE_SIZE", MAX_FILE_BYTES + "B",
                "QUEUELAB_CORS_ALLOWED_ORIGINS", DASHBOARD_ORIGIN)));
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
            assertThat(api.isAlive()).as("la API sigue viva:%n%s", api.tail(30)).isTrue();
            assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
        });
        // Retención de la entrada de un COMPLETED acortada; la de un FAILED (7 días) se deja como está.
        worker = AppProcess.start("worker", merge(env, Map.of(
                "QUEUELAB_CLEANUP_INITIAL_DELAY", "1s",
                "QUEUELAB_CLEANUP_INTERVAL", "1s",
                "QUEUELAB_CLEANUP_COMPLETED_INPUT_RETENTION", "4s")));
    }

    @AfterAll
    static void stop() {
        if (worker != null) {
            worker.close();
        }
        if (api != null) {
            api.close();
        }
        RABBIT.stop();
        POSTGRES.stop();
    }

    @Test
    void sampleCsvIsUploadedProcessedAndItsStatisticsDownloaded() throws Exception {
        HttpResponse<String> created = upload("muestra.csv", "text/csv", SAMPLE.getBytes(StandardCharsets.UTF_8));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(created.body()).get("id").asString());

        JsonNode done = awaitStatus(id, "COMPLETED");

        assertThat(done.get("result").asString()).isEqualTo("CSV procesado: 3 filas, 3 columnas");
        assertThat(done.get("error").isNull()).isTrue();
        assertThat(done.get("attempts").asInt()).isEqualTo(1);
        JsonNode file = done.get("resultFile");
        assertThat(file.get("name").asString()).isEqualTo(id + ".stats.json");
        assertThat(file.get("contentType").asString()).isEqualTo("application/json");
        assertThat(file.get("downloadUrl").asString()).isEqualTo("/api/v1/jobs/" + id + "/result");

        HttpResponse<byte[]> download = getBytes(file.get("downloadUrl").asString());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                d -> assertThat(d).startsWith("attachment").contains(id + ".stats.json"));
        assertThat(download.body()).hasSize(file.get("size").asInt());

        JsonNode stats = JSON.readTree(download.body());
        assertThat(stats.get("rows").asLong()).isEqualTo(3);
        JsonNode price = stats.get("columns").get(2);
        assertThat(price.get("name").asString()).isEqualTo("price");
        assertThat(price.get("type").asString()).isEqualTo("number");
        assertThat(price.get("empty").asLong()).isEqualTo(1);
        assertThat(price.get("sum").decimalValue()).isEqualByComparingTo("14.5");
        assertThat(price.get("mean").decimalValue()).isEqualByComparingTo("7.25");
        assertThat(stats.get("columns").get(1).get("type").asString()).isEqualTo("text");
    }

    @Test
    void malformedRowFailsOnceWithItsLineNumberAndOffersNoResult() throws Exception {
        UUID id = submitCsv("id,name\n1,Ana\n2,Luis,extra\n3,Eva\n");

        JsonNode failed = awaitStatus(id, "FAILED");

        assertThat(failed.get("error").asString()).startsWith("Línea 3:");
        assertThat(failed.get("attempts").asInt()).isEqualTo(1);
        assertThat(failed.get("resultFile").isNull()).isTrue();
        assertThat(failed.get("result").isNull()).isTrue();
        HttpResponse<String> result = get("/api/v1/jobs/" + id + "/result");
        assertThat(result.statusCode()).isEqualTo(409);
        // Un FAILED conserva la entrada para poder reintentarlo a mano.
        assertThat(Files.exists(storage.resolve("inputs/" + id + ".csv"))).isTrue();
    }

    @Test
    void largeFileIsStreamedThroughUploadProcessingAndDownload() throws Exception {
        long rows = 100_000;
        StringBuilder csv = new StringBuilder("n,label\n");
        for (long i = 1; i <= rows; i++) {
            csv.append(i).append(",fila-").append(i).append('\n');
        }
        byte[] content = csv.toString().getBytes(StandardCharsets.UTF_8);
        assertThat(content.length).isBetween((int) (MAX_FILE_BYTES / 2), (int) MAX_FILE_BYTES);

        UUID id = submitCsv(content);
        JsonNode done = awaitStatus(id, "COMPLETED");

        assertThat(done.get("result").asString()).isEqualTo("CSV procesado: 100000 filas, 2 columnas");
        JsonNode stats = JSON.readTree(getBytes("/api/v1/jobs/" + id + "/result").body());
        JsonNode n = stats.get("columns").get(0);
        assertThat(stats.get("rows").asLong()).isEqualTo(rows);
        assertThat(n.get("min").decimalValue()).isEqualByComparingTo("1");
        assertThat(n.get("max").decimalValue()).isEqualByComparingTo(String.valueOf(rows));
        assertThat(n.get("sum").decimalValue()).isEqualByComparingTo(String.valueOf(rows * (rows + 1) / 2));
    }

    @Test
    void oversizedFileIsRejectedWith413AndNothingIsCreated() throws Exception {
        long before = count("SELECT count(*) FROM jobs");
        byte[] content = new byte[(int) MAX_FILE_BYTES + 1];
        java.util.Arrays.fill(content, (byte) 'a');
        content[content.length - 1] = '\n';

        HttpResponse<String> response = upload("grande.csv", "text/csv", content);

        assertThat(response.statusCode()).isEqualTo(413);
        // Sin esta cabecera el navegador del dashboard vería un fallo de red en vez del motivo.
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).hasValue(DASHBOARD_ORIGIN);
        assertThat(JSON.readTree(response.body()).get("detail").asString()).contains("tamaño máximo");
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(before);
    }

    @Test
    void wrongFormatAndEmptyFilesAreRejectedBeforeCreatingAnyJob() throws Exception {
        long before = count("SELECT count(*) FROM jobs");

        HttpResponse<String> notCsv = upload("foto.png", "image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        HttpResponse<String> empty = upload("vacio.csv", "text/csv", new byte[0]);

        assertThat(notCsv.statusCode()).isEqualTo(415);
        assertThat(empty.statusCode()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM jobs")).isEqualTo(before);
    }

    @Test
    void inputIsCleanedAfterCompletionButTheResultStaysDownloadable() throws Exception {
        UUID id = submitCsv(SAMPLE);
        awaitStatus(id, "COMPLETED");
        Path input = storage.resolve("inputs/" + id + ".csv");

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(Files.exists(input)).as("la entrada se borra tras la retención%n%s", worker.tail(30)).isFalse());

        // Con la entrada ya borrada la referencia está anulada y el resultado sigue ahí.
        assertThat(inputRef(id)).isNull();
        HttpResponse<byte[]> download = getBytes("/api/v1/jobs/" + id + "/result");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(download.body()).get("rows").asLong()).isEqualTo(3);
        assertThat(job(id).get("status").asString()).isEqualTo("COMPLETED");
    }

    private static UUID submitCsv(String content) throws Exception {
        return submitCsv(content.getBytes(StandardCharsets.UTF_8));
    }

    private static UUID submitCsv(byte[] content) throws Exception {
        HttpResponse<String> response = upload("datos.csv", "text/csv", content);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
    }

    private static HttpResponse<String> upload(String filename, String contentType, byte[] content) throws Exception {
        String boundary = "queuelab-" + UUID.randomUUID();
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs/csv"))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .header("Origin", DASHBOARD_ORIGIN)
                        .POST(HttpRequest.BodyPublishers.ofByteArrays(java.util.List.of(head, content, tail))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(job(id).get("status").asString())
                        .as("estado de %s%n--- worker ---%n%s", id, worker.tail(30)).isEqualTo(expected));
        return job(id);
    }

    private static JsonNode job(UUID id) {
        try {
            HttpResponse<String> response = get("/api/v1/jobs/" + id);
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static long count(String sql) {
        return scalar(sql, null, 0L);
    }

    private static String inputRef(UUID jobId) {
        return scalar("SELECT input_ref FROM jobs WHERE id = ?", jobId, null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T scalar(String sql, UUID jobId, T defaultValue) {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = c.prepareStatement(sql)) {
            if (jobId != null) {
                statement.setObject(1, jobId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? (T) rs.getObject(1) : defaultValue;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        var merged = new java.util.HashMap<>(a);
        merged.putAll(b);
        return merged;
    }
}
