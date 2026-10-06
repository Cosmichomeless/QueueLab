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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
 * Los logs de API y worker (en JSON, como en producción) cuentan la historia de un trabajo con el
 * <b>mismo id de correlación</b>, y no contienen el contenido del fichero.
 */
class LoggingEndToEndTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** Dato de una celda que no debe aparecer jamás en un log. */
    static final String SECRET_CELL = "CELDA-CONFIDENCIAL-7731";

    static AppProcess api;
    static AppProcess worker;
    static String apiUrl;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        RABBIT.start();
        int apiPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            apiPort = socket.getLocalPort();
        }
        apiUrl = "http://localhost:" + apiPort;
        Map<String, String> env = new HashMap<>(Map.of(
                "QUEUELAB_DB_URL", POSTGRES.getJdbcUrl(),
                "QUEUELAB_DB_USER", POSTGRES.getUsername(),
                "QUEUELAB_DB_PASSWORD", POSTGRES.getPassword(),
                "QUEUELAB_RABBITMQ_HOST", "localhost",
                "QUEUELAB_RABBITMQ_PORT", String.valueOf(RABBIT.getAmqpPort()),
                "QUEUELAB_RABBITMQ_USER", RABBIT.getAdminUsername(),
                "QUEUELAB_RABBITMQ_PASSWORD", RABBIT.getAdminPassword(),
                "QUEUELAB_LOG_FORMAT", "logstash"));
        Map<String, String> apiEnv = new HashMap<>(env);
        apiEnv.put("SERVER_PORT", String.valueOf(apiPort));
        api = AppProcess.start("api", apiEnv);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
            assertThat(api.isAlive()).as("la API sigue viva:%n%s", api.tail(30)).isTrue();
            assertThat(send("GET", "/actuator/health", null, null).statusCode()).isEqualTo(200);
        });
        worker = AppProcess.start("worker", env);
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
    void aJobIsTraceableAcrossApiPublisherAndWorkerWithOneCorrelationId() throws Exception {
        String correlationId = "e2e-" + UUID.randomUUID();
        HttpResponse<String> created = upload("secreto.csv", "id,dato\n1," + SECRET_CELL + "\n", correlationId);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat(created.headers().firstValue("X-Correlation-Id")).hasValue(correlationId);
        UUID jobId = UUID.fromString(JSON.readTree(created.body()).get("id").asString());

        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(messagesFor(worker, correlationId)).as("worker:%n%s", worker.tail(20))
                        .anyMatch(m -> m.contains("completado")));

        // Los tres actores hablan del mismo trabajo bajo el mismo id.
        List<JsonNode> apiLines = linesFor(api, correlationId);
        assertThat(apiLines).extracting(l -> l.path("message").asString()).anyMatch(m -> m.contains("aceptado"))
                .anyMatch(m -> m.contains("publicado"));
        assertThat(apiLines).allSatisfy(l -> assertThat(l.path("jobId").asString()).isEqualTo(jobId.toString()));
        List<JsonNode> workerLines = linesFor(worker, correlationId);
        assertThat(workerLines).extracting(l -> l.path("message").asString()).anyMatch(m -> m.contains("en ejecución"))
                .anyMatch(m -> m.contains("completado"));
        assertThat(workerLines).allSatisfy(l -> assertThat(l.path("jobId").asString()).isEqualTo(jobId.toString()));

        // Nada del contenido del fichero acaba en los logs.
        assertThat(String.join("\n", api.lines())).doesNotContain(SECRET_CELL);
        assertThat(String.join("\n", worker.lines())).doesNotContain(SECRET_CELL);
    }

    @Test
    void anInvalidOrMissingCorrelationIdIsReplacedNotEchoed() throws Exception {
        String hostile = "no valido \"{x}\"";
        HttpResponse<String> response = send("GET", "/api/v1/jobs", "X-Correlation-Id", hostile);
        String returned = response.headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(returned).isNotEqualTo(hostile).matches("[0-9a-f-]{36}");

        String generated = send("GET", "/api/v1/jobs", null, null).headers().firstValue("X-Correlation-Id").orElseThrow();
        assertThat(generated).matches("[0-9a-f-]{36}");
    }

    private static List<String> messagesFor(AppProcess process, String correlationId) throws IOException {
        return linesFor(process, correlationId).stream().map(l -> l.path("message").asString()).toList();
    }

    /** Líneas JSON del proceso cuyo MDC lleva ese id de correlación. */
    private static List<JsonNode> linesFor(AppProcess process, String correlationId) throws IOException {
        List<JsonNode> matching = new ArrayList<>();
        for (String line : process.lines()) {
            if (!line.startsWith("{")) {
                continue;
            }
            JsonNode node = JSON.readTree(line);
            if (correlationId.equals(node.path("correlationId").asString())) {
                matching.add(node);
            }
        }
        return matching;
    }

    private static HttpResponse<String> send(String method, String path, String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiUrl + path)).method(method,
                HttpRequest.BodyPublishers.noBody());
        if (header != null) {
            request.header(header, value);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> upload(String filename, String content, String correlationId) throws Exception {
        String boundary = "queuelab-" + UUID.randomUUID();
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs/csv"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-Correlation-Id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
