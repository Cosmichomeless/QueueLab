package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;

/** La migración de {@code jobs} y el repositorio funcionan contra PostgreSQL real. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class JobRepositoryTest {

    @Autowired
    JobRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    private static Instant now() {
        // PostgreSQL guarda microsegundos.
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    void savesAndLoadsIdTypeStatusAndTimestamps() {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", now());

        repository.insert(job);

        assertThat(repository.findById(job.id())).contains(job);
    }

    @Test
    void unknownIdIsEmpty() {
        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void persistsLifecycleTransitions() {
        Job queued = Job.queued(UUID.randomUUID(), "image-resize", now());
        repository.insert(queued);

        Job running = queued.transitionTo(JobStatus.RUNNING, now());
        assertThat(repository.update(running, JobStatus.QUEUED)).isTrue();
        Job completed = running.transitionTo(JobStatus.COMPLETED, now());
        assertThat(repository.update(completed, JobStatus.RUNNING)).isTrue();

        Job stored = repository.findById(queued.id()).orElseThrow();
        assertThat(stored).isEqualTo(completed);
        assertThat(stored.startedAt()).isNotNull();
        assertThat(stored.finishedAt()).isNotNull();
    }

    @Test
    void staleUpdateIsRejectedWhenStatusAlreadyChanged() {
        Job queued = Job.queued(UUID.randomUUID(), "csv-import", now());
        repository.insert(queued);
        Job running = queued.transitionTo(JobStatus.RUNNING, now());
        assertThat(repository.update(running, JobStatus.QUEUED)).isTrue();

        // Otro proceso intenta repetir la misma transición desde un estado que ya no es el real.
        assertThat(repository.update(running, JobStatus.QUEUED)).isFalse();
        assertThat(repository.findById(queued.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    void databaseRejectsUnknownStatus() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO jobs (id, type, status, created_at, updated_at)
                VALUES (gen_random_uuid(), 'x', 'PAUSED', now(), now())
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
