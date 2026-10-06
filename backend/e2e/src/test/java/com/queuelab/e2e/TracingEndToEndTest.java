package com.queuelab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Una sola traza une la petición HTTP, el outbox y la ejecución, con <b>dos procesos reales</b> (API y worker)
 * exportando por OTLP a un receptor de pruebas. La petición lleva un {@code traceparent} conocido: ese mismo id
 * debe aparecer en los spans que exportan los dos procesos.
 */
class TracingEndToEndTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** Cuerpos OTLP/HTTP (protobuf) recibidos, ya descomprimidos. */
    static final List<byte[]> EXPORTS = new CopyOnWriteArrayList<>();

    static HttpServer collector;
    static AppProcess api;
    static AppProcess worker;
    static String apiUrl;

    @BeforeAll
    static void start() throws Exception {
        collector = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        collector.createContext("/v1/traces", exchange -> {
            try (var in = exchange.getRequestBody()) {
                byte[] raw = in.readAllBytes();
                if ("gzip".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                    raw = new GZIPInputStream(new java.io.ByteArrayInputStream(raw)).readAllBytes();
                }
                EXPORTS.add(raw);
            }
            // Respuesta OTLP vacía (ExportTraceServiceResponse sin campos) y correcta.
            exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        collector.start();

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
                "QUEUELAB_TRACING_EXPORT_ENABLED", "true",
                "QUEUELAB_TRACING_ENDPOINT", "http://localhost:" + collector.getAddress().getPort() + "/v1/traces"));
        Map<String, String> apiEnv = new HashMap<>(env);
        apiEnv.put("SERVER_PORT", String.valueOf(apiPort));
        api = AppProcess.start("api", apiEnv);
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
            assertThat(api.isAlive()).as("la API sigue viva:%n%s", api.tail(30)).isTrue();
            assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + "/actuator/health")).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
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
        if (collector != null) {
            collector.stop(0);
        }
        RABBIT.stop();
        POSTGRES.stop();
    }

    @Test
    void oneTraceUnitesTheRequestTheOutboxAndTheWorkerExecutionAcrossProcesses() throws Exception {
        byte[] traceId = new byte[16];
        new java.security.SecureRandom().nextBytes(traceId);
        traceId[0] |= 1; // un id de traza nunca es todo ceros
        String traceparent = "00-" + HexFormat.of().formatHex(traceId) + "-00f067aa0ba902b7-01";

        HttpResponse<String> created = upload("traza.csv", "id,dato\n1,a\n2,b\n", traceparent);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID jobId = UUID.fromString(JSON.readTree(created.body()).get("id").asString());

        // Los spans salen en lotes (cada pocos segundos): se espera a ver los de los dos procesos con ese id.
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            List<byte[]> withTrace = EXPORTS.stream().filter(body -> indexOf(body, traceId) >= 0).toList();
            assertThat(withTrace).as("exportaciones con la traza %s; worker:%n%s", HexFormat.of().formatHex(traceId),
                    worker.tail(20)).isNotEmpty();
            // API: la petición, la espera en el outbox y la publicación.
            assertThat(withTrace).anyMatch(b -> contains(b, "queuelab-api") && contains(b, "outbox.wait")
                    && contains(b, "outbox.publish"));
            // Worker: la espera en la cola, el procesamiento y la ejecución, en la misma traza.
            assertThat(withTrace).anyMatch(b -> contains(b, "queuelab-worker") && contains(b, "queue.wait")
                    && contains(b, "job.process") && contains(b, "job.execute"));
        });

        // Y no es casualidad: el trabajo se completó de verdad.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(HTTP.send(
                HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs/" + jobId)).build(),
                HttpResponse.BodyHandlers.ofString()).body()).contains("COMPLETED"));
    }

    private static boolean contains(byte[] body, String text) {
        return indexOf(body, text.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static HttpResponse<String> upload(String filename, String content, String traceparent) throws IOException,
            InterruptedException {
        String boundary = "queuelab-" + UUID.randomUUID();
        byte[] body = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n" + content + "\r\n--" + boundary + "--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        return HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs/csv"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("traceparent", traceparent)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
