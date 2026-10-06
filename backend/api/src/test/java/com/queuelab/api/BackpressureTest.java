package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.queue.QueueBackpressure;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.api.support.PostgresTestConfiguration;

/** Contrapresión: con 3 trabajos esperando (umbral de la prueba) los envíos nuevos se rechazan con 503. */
@SpringBootTest(properties = { "queuelab.backpressure.max-pending=3", "queuelab.backpressure.retry-after=7s" })
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class BackpressureTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    @BeforeEach
    void clean() throws Exception {
        jdbc.sql("DELETE FROM jobs").update();
        Path inputs = Files.createDirectories(storageDirectory.toAbsolutePath().resolve("inputs"));
        try (Stream<Path> files = Files.list(inputs)) {
            files.forEach(f -> f.toFile().delete());
        }
    }

    private Job queued() {
        Job job = Job.queued(UUID.randomUUID(), "noop", Instant.now());
        jobs.insert(job);
        return job;
    }

    private void queue(int n) {
        for (int i = 0; i < n; i++) {
            queued();
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private org.springframework.test.web.servlet.ResultActions submit() throws Exception {
        return mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"noop\"}"));
    }

    @Test
    void belowTheThresholdSubmissionsAreAccepted() throws Exception {
        queue(2);

        submit().andExpect(status().isCreated());
        // Con el nuevo ya hay 3: el siguiente se rechaza.
        submit().andExpect(status().isServiceUnavailable());
    }

    @Test
    void atTheThresholdTheApiAnswersWithAControlledErrorAndSavesNothing() throws Exception {
        queue(3);

        submit()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.title").value("Cola saturada"))
                .andExpect(jsonPath("$.pending").value(3))
                .andExpect(jsonPath("$.maxPending").value(3))
                .andExpect(jsonPath("$.retryAfterSeconds").value(7));

        assertThat(count("jobs")).isEqualTo(3);
        assertThat(count("outbox_events")).isEqualTo(0);
    }

    @Test
    void retryingJobsCountButRunningAndFinishedOnesDoNot() throws Exception {
        // 2 esperando + 1 RETRYING = 3 → saturada.
        queue(2);
        Job retrying = queued();
        jdbc.sql("UPDATE jobs SET status = 'RETRYING' WHERE id = :id").param("id", retrying.id()).update();
        submit().andExpect(status().isServiceUnavailable());

        // Los que ya tienen worker o terminaron no cuentan.
        jdbc.sql("UPDATE jobs SET status = 'RUNNING' WHERE id = :id").param("id", retrying.id()).update();
        submit().andExpect(status().isCreated());
        jdbc.sql("DELETE FROM jobs WHERE status = 'QUEUED'").update();
        queue(1);
        jdbc.sql("UPDATE jobs SET status = 'COMPLETED' WHERE status = 'QUEUED'").update();
        jdbc.sql("UPDATE jobs SET status = 'FAILED' WHERE status = 'RUNNING'").update();
        queue(2);
        submit().andExpect(status().isCreated());
    }

    @Test
    void csvUploadsAreRejectedBeforeTheFileIsStored() throws Exception {
        queue(3);

        mvc.perform(multipart("/api/v1/jobs/csv")
                        .file(new MockMultipartFile("file", "datos.csv", "text/csv", "a,b\n1,2\n".getBytes())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"));

        assertThat(count("jobs")).isEqualTo(3);
        try (Stream<Path> files = Files.list(storageDirectory.toAbsolutePath().resolve("inputs"))) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void replayingAnAcceptedIdempotencyKeyIsNotRejected() throws Exception {
        queue(2);
        mvc.perform(post("/api/v1/jobs").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"noop\"}")).andExpect(status().isCreated());
        // Ya saturada (3 de 3): la misma petición no encola nada y se contesta con el trabajo existente.
        mvc.perform(post("/api/v1/jobs").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"noop\"}")).andExpect(status().isOk());
        // Una clave nueva sí se rechaza.
        mvc.perform(post("/api/v1/jobs").header("Idempotency-Key", "k-2").contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"noop\"}")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void manualRetryIsRejectedWhileSaturatedAndWorksAfterwards() throws Exception {
        Job failed = queued();
        jdbc.sql("UPDATE jobs SET status = 'FAILED' WHERE id = :id").param("id", failed.id()).update();
        queue(3);

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isServiceUnavailable());
        assertThat(jobs.findById(failed.id()).orElseThrow().status().name()).isEqualTo("FAILED");

        jdbc.sql("DELETE FROM jobs WHERE status = 'QUEUED'").update();
        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isAccepted());
    }

    @Test
    void theQueueEndpointExposesTheMeasurement() throws Exception {
        queue(2);
        mvc.perform(get("/api/v1/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.maxPending").value(3))
                .andExpect(jsonPath("$.saturated").value(false))
                .andExpect(jsonPath("$.retryAfterSeconds").value(7));

        queue(1);
        mvc.perform(get("/api/v1/queue")).andExpect(jsonPath("$.saturated").value(true));
    }

    @Test
    void pendingIsCappedAtTheThresholdSoTheCountStaysCheap() throws Exception {
        queue(10);
        mvc.perform(get("/api/v1/queue"))
                .andExpect(jsonPath("$.pending").value(3))
                .andExpect(jsonPath("$.saturated").value(true));
    }

    @Test
    void unsafeLimitsAreRejectedAtStartup() {
        assertThatThrownBy(() -> new QueueBackpressure(jobs, 0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-pending debe ser al menos 1");
        assertThatThrownBy(() -> new QueueBackpressure(jobs, 10, Duration.ofMillis(200)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retry-after debe ser al menos 1 s");
    }
}
