package com.queuelab.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class GetJobTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JobRepository jobs;

    @Test
    void existingIdReturnsPublicRepresentation() throws Exception {
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        Job job = Job.queued(UUID.randomUUID(), "csv-import", t0)
                .transitionTo(JobStatus.RUNNING, t0.plus(5, ChronoUnit.SECONDS));
        jobs.insert(Job.queued(job.id(), job.type(), t0));
        jobs.update(job, JobStatus.QUEUED);

        mvc.perform(get("/api/v1/jobs/{id}", job.id()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(job.id().toString()))
                .andExpect(jsonPath("$.type").value("csv-import"))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T10:00:00Z"))
                .andExpect(jsonPath("$.startedAt").value("2026-01-01T10:00:05Z"))
                .andExpect(jsonPath("$.finishedAt").doesNotExist());
    }

    @Test
    void unknownIdReturns404WithConsistentError() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(get("/api/v1/jobs/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Trabajo no encontrado"))
                .andExpect(jsonPath("$.detail").value("No existe ningún trabajo con id " + id));
    }

    @Test
    void malformedIdReturns400WithTheSameErrorFormat() throws Exception {
        mvc.perform(get("/api/v1/jobs/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400));
    }
}
