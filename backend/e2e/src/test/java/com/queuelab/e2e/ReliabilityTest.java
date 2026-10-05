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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
 * Fallos esperables con los jars reales de la API y del worker como procesos aparte, contra PostgreSQL y
 * RabbitMQ reales: envío repetido, entrega duplicada, caída del broker con el worker conectado y worker
 * caído a mitad de un trabajo. Todo se observa por HTTP y SQL.
 *
 * <p>Los tests dependen del orden: el último sustituye al worker por uno nuevo.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReliabilityTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static Map<String, String> env;
    static AppProcess api;
    static AppProcess worker;
    static String apiUrl;

    @BeforeAll
    static void start() throws Exception {
        int amqpPort = freePort();
        RABBIT.setPortBindings(List.of(amqpPort + ":5672"));
        POSTGRES.start();
        RABBIT.start();

        int apiPort = freePort();
        apiUrl = "http://localhost:" + apiPort;
        env = Map.of(
                "QUEUELAB_DB_URL", POSTGRES.getJdbcUrl(),
                "QUEUELAB_DB_USER", POSTGRES.getUsername(),
                "QUEUELAB_DB_PASSWORD", POSTGRES.getPassword(),
                "QUEUELAB_RABBITMQ_HOST", "localhost",
                "QUEUELAB_RABBITMQ_PORT", String.valueOf(amqpPort),
                "QUEUELAB_RABBITMQ_USER", RABBIT.getAdminUsername(),
                "QUEUELAB_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());

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
    void repeatedAndSimultaneousSubmissionsWithTheSameKeyRunTheJobOnce() throws Exception {
        String key = "e2e-" + UUID.randomUUID();
        List<CompletableFuture<UUID>> clients = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            clients.add(CompletableFuture.supplyAsync(() -> submit("noop", key)));
        }
        UUID id = clients.get(0).get();
        for (var client : clients) {
            assertThat(client.get()).isEqualTo(id);
        }
        assertThat(submit("noop", key)).isEqualTo(id);

        awaitStatus(id, "COMPLETED");

        assertThat(count("SELECT count(*) FROM jobs WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events WHERE job_id = ?", id)).isEqualTo(1);
        assertThat(job(id).get("attempts").asInt()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void redeliveredMessageDoesNotRunACompletedJobAgain() throws Exception {
        UUID id = submit("noop", null);
        JsonNode done = awaitStatus(id, "COMPLETED");

        // Mismo evento publicado otra vez, como tras un fallo entre el envío y su confirmación.
        for (int i = 0; i < 3; i++) {
            execute("UPDATE outbox_events SET published_at = NULL, available_at = now() WHERE job_id = ?", id);
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
                    count("SELECT count(*) FROM outbox_events WHERE job_id = ? AND published_at IS NOT NULL", id))
                    .isEqualTo(1));
        }
        // Margen para que el worker consuma las copias y las descarte.
        Thread.sleep(2_000);

        JsonNode after = job(id);
        assertThat(after.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(after.get("attempts").asInt()).isEqualTo(1);
        assertThat(after.get("finishedAt")).isEqualTo(done.get("finishedAt"));
        assertThat(after.get("result")).isEqualTo(done.get("result"));
    }

    @Test
    @Order(3)
    void brokerOutageWithTheWorkerConnectedLosesAndDuplicatesNothing() throws Exception {
        RABBIT.getDockerClient().stopContainerCmd(RABBIT.getContainerId()).exec();
        List<UUID> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                ids.add(submit("noop", null));
            }
            // El despachador lo intenta y falla (el lote se corta en el primer error): nada se pierde ni se publica.
            await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(
                    "SELECT coalesce(max(attempts), 0) FROM outbox_events WHERE job_id = ? OR job_id = ? OR job_id = ?",
                    ids)).isGreaterThanOrEqualTo(1));
            for (UUID id : ids) {
                assertThat(count("SELECT count(*) FROM outbox_events WHERE job_id = ? AND published_at IS NULL", id))
                        .isEqualTo(1);
                assertThat(status(id)).isEqualTo("QUEUED");
            }
            assertThat(worker.isAlive()).as("el worker sigue vivo sin broker").isTrue();
        } finally {
            RABBIT.getDockerClient().startContainerCmd(RABBIT.getContainerId()).exec();
        }

        // El worker se reconecta solo y cada trabajo se ejecuta exactamente una vez.
        for (UUID id : ids) {
            awaitStatus(id, "COMPLETED");
            assertThat(job(id).get("attempts").asInt()).isEqualTo(1);
        }
    }

    @Test
    @Order(4)
    void jobsLeftRunningByACrashedWorkerAreRetriedOrFailedToTheDeadLetterQueue() throws Exception {
        // El worker muere sin cerrar nada: sus trabajos quedan RUNNING con el lease sin renovar.
        worker.close();
        UUID withAttemptsLeft = crashedMidExecution(1);
        UUID exhausted = crashedMidExecution(3);

        worker = AppProcess.start("worker", merge(env, Map.of("QUEUELAB_WORKER_RECOVERY_INTERVAL", "1s")));

        // Con intentos disponibles se reprograma, el nuevo worker lo ejecuta y el intento caído queda contado.
        JsonNode recovered = awaitStatus(withAttemptsLeft, "COMPLETED");
        assertThat(recovered.get("attempts").asInt()).isEqualTo(2);
        assertThat(recovered.get("error").isNull()).isTrue();

        // Sin intentos disponibles acaba FAILED y avisa a la DLQ por el outbox.
        JsonNode failed = awaitStatus(exhausted, "FAILED");
        assertThat(failed.get("attempts").asInt()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(
                "SELECT count(*) FROM outbox_events WHERE job_id = ? AND event_type = 'JOB_DEAD_LETTERED' "
                        + "AND published_at IS NOT NULL", exhausted)).isEqualTo(1));
    }

    /** Inserta lo que dejaría un worker que muere durante su intento {@code attempts}. */
    private static UUID crashedMidExecution(int attempts) {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO jobs (id, type, status, attempts, created_at, updated_at, started_at, lease_expires_at) "
                + "VALUES (?, 'noop', 'RUNNING', " + attempts + ", now(), now(), now(), now() - interval '1 minute')", id);
        return id;
    }

    private static UUID submit(String type, String idempotencyKey) {
        try {
            var request = HttpRequest.newBuilder(URI.create(apiUrl + "/api/v1/jobs"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"" + type + "\"}"));
            if (idempotencyKey != null) {
                request.header("Idempotency-Key", idempotencyKey);
            }
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
            return UUID.fromString(JSON.readTree(response.body()).get("id").asString());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
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

    private static long count(String sql, Object param) {
        try (Connection c = connection(); var statement = c.prepareStatement(sql)) {
            if (param instanceof List<?> params) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setObject(i + 1, params.get(i));
                }
            } else {
                statement.setObject(1, param);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void execute(String sql, Object param) {
        try (Connection c = connection(); var statement = c.prepareStatement(sql)) {
            statement.setObject(1, param);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        var merged = new HashMap<>(a);
        merged.putAll(b);
        return merged;
    }
}
