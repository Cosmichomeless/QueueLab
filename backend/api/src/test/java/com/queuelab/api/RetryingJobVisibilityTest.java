package com.queuelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

/** La API muestra el estado {@code RETRYING}, el número de intento y el último error. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class RetryingJobVisibilityTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Test
    void retryingJobShowsItsAttemptAndLastError() throws Exception {
        Job queued = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(queued);
        Job running = jobs.claim(queued.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        jobs.finishAttempt(running.retrying("El servicio externo no responde", Instant.now()));

        mvc.perform(get("/api/v1/jobs/" + queued.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(JobStatus.RETRYING.name()))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.error").value("El servicio externo no responde"))
                .andExpect(jsonPath("$.finishedAt").doesNotExist());
    }

    @Test
    void newJobStartsWithZeroAttempts() throws Exception {
        Job queued = Job.queued(UUID.randomUUID(), "noop", Instant.now());
        jobs.insert(queued);

        mvc.perform(get("/api/v1/jobs/" + queued.id()))
                .andExpect(jsonPath("$.attempts").value(0));
    }

    @Test
    void exhaustedJobShowsCauseAndAttemptsAndNeverAnyInput() throws Exception {
        Job queued = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(queued);
        Job running = jobs.claim(queued.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        jobs.finishAttempt(running.failed("El servicio externo no responde", Instant.now()));

        mvc.perform(get("/api/v1/jobs/" + queued.id()))
                .andExpect(jsonPath("$.status").value(JobStatus.FAILED.name()))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.error").value("El servicio externo no responde"))
                // Solo campos públicos conocidos: no hay payload ni entrada del trabajo.
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$.payload").doesNotExist())
                .andExpect(jsonPath("$.input").doesNotExist());
    }
}
