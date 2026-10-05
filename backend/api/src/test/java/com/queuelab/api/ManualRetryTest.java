package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

/** Reintento manual: solo para FAILED, sin duplicados, con historial y republicado por el outbox. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class ManualRetryTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    /** Un trabajo que llegó a FAILED tras {@code attempts} intentos con el error indicado. */
    private Job failedJob(int attempts, String error) {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now().minusSeconds(60));
        jobs.insert(job);
        Job running = null;
        for (int i = 0; i < attempts; i++) {
            running = jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
            if (i < attempts - 1) {
                jdbc.sql("UPDATE jobs SET status = 'RETRYING' WHERE id = :id").param("id", job.id()).update();
            }
        }
        assertThat(jobs.finishAttempt(running.failed(error, Instant.now()))).isTrue();
        return jobs.findById(job.id()).orElseThrow();
    }

    private Job inState(JobStatus target) {
        return switch (target) {
            case FAILED -> failedJob(3, "boom");
            case QUEUED -> {
                Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
                jobs.insert(job);
                yield job;
            }
            case RUNNING -> {
                Job job = inState(JobStatus.QUEUED);
                yield jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
            }
            case RETRYING -> {
                Job running = inState(JobStatus.RUNNING);
                jobs.finishAttempt(running.retrying("transitorio", Instant.now()));
                yield jobs.findById(running.id()).orElseThrow();
            }
            case COMPLETED -> {
                Job running = inState(JobStatus.RUNNING);
                jobs.finishAttempt(running.completed("ok", Instant.now()));
                yield jobs.findById(running.id()).orElseThrow();
            }
        };
    }

    private long events(UUID id) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE job_id = :id AND event_type = 'JOB_QUEUED'")
                .param("id", id).query(Long.class).single();
    }

    private long retries(UUID id) {
        return jdbc.sql("SELECT count(*) FROM job_retries WHERE job_id = :id").param("id", id)
                .query(Long.class).single();
    }

    @Test
    void failedJobGoesBackToQueuedAndKeepsItsLastError() throws Exception {
        Job failed = failedJob(3, "El servicio externo no responde");

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id()))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/jobs/" + failed.id())))
                .andExpect(jsonPath("$.id").value(failed.id().toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.finishedAt").doesNotExist())
                .andExpect(jsonPath("$.error").value("El servicio externo no responde"));

        Job stored = jobs.findById(failed.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.finishedAt()).isNull();
        assertThat(stored.startedAt()).isEqualTo(failed.startedAt());
    }

    @Test
    void retryIsRepublishedThroughTheOutboxInTheSameTransaction() throws Exception {
        Job failed = failedJob(1, "boom");
        assertThat(events(failed.id())).isZero();

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isAccepted());

        assertThat(events(failed.id())).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE job_id = :id AND published_at IS NULL "
                + "AND available_at IS NULL").param("id", failed.id()).query(Long.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void jobsInAnyOtherStateAreRejectedAndNothingChanges(JobStatus state) throws Exception {
        Job job = inState(state);
        long eventsBefore = events(job.id());

        mvc.perform(post("/api/v1/jobs/{id}/retry", job.id()))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("El trabajo no se puede reintentar"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(state.name())));

        assertThat(jobs.findById(job.id()).orElseThrow()).isEqualTo(job);
        assertThat(events(job.id())).isEqualTo(eventsBefore);
        assertThat(retries(job.id())).isZero();
    }

    @Test
    void unknownJobIs404AndMalformedIdIs400() throws Exception {
        mvc.perform(post("/api/v1/jobs/{id}/retry", UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/{id}/retry", "no-es-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    void retryingTwiceDoesNotCreateASecondSubmission() throws Exception {
        Job failed = failedJob(3, "boom");

        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isConflict());

        assertThat(events(failed.id())).isEqualTo(1);
        assertThat(retries(failed.id())).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesYieldExactlyOneSubmission() throws Exception {
        Job failed = failedJob(3, "boom");
        int clients = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clients);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andReturn().getResponse()
                            .getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get());
            }
            assertThat(statuses).containsOnly(202, 409);
            assertThat(statuses.stream().filter(s -> s == 202)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(events(failed.id())).isEqualTo(1);
        assertThat(retries(failed.id())).isEqualTo(1);
    }

    @Test
    void historyKeepsEveryRetryWithWhatWasConsumedBefore() throws Exception {
        Job failed = failedJob(3, "primer fallo");
        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isAccepted());

        // El nuevo intento vuelve a fallar (esta vez a la primera) y se reintenta otra vez.
        Job running = jobs.claim(failed.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(running.attempts()).isEqualTo(1);
        jobs.finishAttempt(running.failed("segundo fallo", Instant.now()));
        mvc.perform(post("/api/v1/jobs/{id}/retry", failed.id())).andExpect(status().isAccepted());

        mvc.perform(get("/api/v1/jobs/{id}/retries", failed.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].attempts").value(3))
                .andExpect(jsonPath("$[0].error").value("primer fallo"))
                .andExpect(jsonPath("$[1].attempts").value(1))
                .andExpect(jsonPath("$[1].error").value("segundo fallo"))
                .andExpect(jsonPath("$[0].requestedAt").isNotEmpty());
        assertThat(events(failed.id())).isEqualTo(2);
    }

    @Test
    void historyOfAJobWithoutRetriesIsEmptyAndOfAnUnknownJobIs404() throws Exception {
        Job failed = failedJob(1, "boom");

        mvc.perform(get("/api/v1/jobs/{id}/retries", failed.id()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/jobs/{id}/retries", UUID.randomUUID())).andExpect(status().isNotFound());
    }
}
