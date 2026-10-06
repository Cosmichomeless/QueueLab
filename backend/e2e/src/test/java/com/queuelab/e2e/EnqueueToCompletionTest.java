package com.queuelab.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Primer flujo asíncrono completo, sin atajos: los jars reales de la API y del worker corren como procesos
 * separados contra PostgreSQL y RabbitMQ reales, y todo se observa por HTTP y SQL.
 *
 * <p>Los tests dependen del orden: el segundo corta RabbitMQ con los procesos ya levantados.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnqueueToCompletionTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static AppProcess api;
    static AppProcess worker;
    static String apiUrl;

    @BeforeAll
    static void start() throws Exception {
        // Puerto fijo para el broker: así sobrevive a parar y arrancar el contenedor.
        int amqpPort = freePort();
        RABBIT.setPortBindings(List.of(amqpPort + ":5672"));
        POSTGRES.start();
        RABBIT.start();

        int apiPort = freePort();
        apiUrl = "http://localhost:" + apiPort;
        Map<String, String> env = Map.of(
                "QUEUELAB_DB_URL", POSTGRES.getJdbcUrl(),
                "QUEUELAB_DB_USER", POSTGRES.getUsername(),
                "QUEUELAB_DB_PASSWORD", POSTGRES.getPassword(),
                "QUEUELAB_RABBITMQ_HOST", "localhost",
                "QUEUELAB_RABBITMQ_PORT", String.valueOf(amqpPort),
                "QUEUELAB_RABBITMQ_USER", RABBIT.getAdminUsername(),
                "QUEUELAB_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());

        // La API migra el esquema: el worker se lanza después, como en producción.
        api = AppProcess.start("api", merge(env, Map.of("SERVER_PORT", String.valueOf(apiPort))));
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).ignoreExceptions().untilAsserted(() -> {
            assertThat(api.isAlive()).as("la API sigue viva:%n%s", api.tail(30)).isTrue();
            assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
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
    @Order(1)
    void submittedJobEndsCompletedByTheWorker() throws Exception {
        UUID id = submit("noop");

        JsonNode done = awaitStatus(id, "COMPLETED");

        assertThat(done.get("result").asString()).isEqualTo("Trabajo de tipo 'noop' completado");
        assertThat(done.get("error").isNull()).isTrue();
        assertThat(done.get("startedAt").isNull()).isFalse();
        assertThat(done.get("finishedAt").isNull()).isFalse();
        assertThat(outboxPublished(id)).isTrue();
    }

    @Test
    @Order(2)
    void outboxKeepsTheJobWhileRabbitMqIsDownAndDeliversItOnRecovery() throws Exception {
        RABBIT.getDockerClient().stopContainerCmd(RABBIT.getContainerId()).exec();
        UUID id;
        try {
            id = submit("noop");

            // La API aceptó el trabajo, el outbox lo conserva con sus reintentos y nadie lo ha procesado.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(
                    () -> assertThat(outboxAttempts(id)).isGreaterThanOrEqualTo(1));
            assertThat(outboxPublished(id)).isFalse();
            assertThat(status(id)).isEqualTo("QUEUED");
        } finally {
            RABBIT.getDockerClient().startContainerCmd(RABBIT.getContainerId()).exec();
        }

        // Al volver el broker, el despachador publica el evento pendiente y el worker lo completa.
        awaitStatus(id, "COMPLETED");
        assertThat(outboxPublished(id)).isTrue();
    }

    private static UUID submit(String type) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"" + type + "\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo("QUEUED");
        return UUID.fromString(body.get("id").asString());
    }

    private static JsonNode awaitStatus(UUID id, String expected) {
        await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(status(id)).as("estado de %s%n--- worker ---%n%s", id, worker.tail(30)).isEqualTo(expected));
        return job(id);
    }

    private static String status(UUID id) {
        return job(id).get("status").asString();
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

    private static boolean outboxPublished(UUID jobId) {
        return query("SELECT published_at IS NOT NULL FROM outbox_events WHERE job_id = ?", jobId, true);
    }

    private static int outboxAttempts(UUID jobId) {
        return query("SELECT attempts FROM outbox_events WHERE job_id = ?", jobId, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T query(String sql, UUID jobId, T defaultValue) {
        try (Connection c = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = c.prepareStatement(sql)) {
            statement.setObject(1, jobId);
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
