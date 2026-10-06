package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.worker.job.StorageCleaner;
import com.queuelab.worker.support.ContainersTestConfiguration;

/** Retención por defecto: entrada COMPLETED 1 h, entrada FAILED 7 d, resultado 30 d, temporales 1 h. */
@SpringBootTest
@Import(ContainersTestConfiguration.class)
class StorageCleanerTest {

    @Autowired
    StorageCleaner cleaner;

    @Autowired
    JobRepository jobs;

    @Autowired
    FileStorage storage;

    @Autowired
    JdbcClient jdbc;

    @Value("${queuelab.storage.directory}")
    Path storageDirectory;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    /** Un trabajo en {@code status} que terminó hace {@code finishedAgo}, con entrada y (si es COMPLETED) resultado. */
    private Job job(JobStatus status, Duration finishedAgo) {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);
        jobs.attachInput(job.id(), store(StorageArea.INPUT, job.id() + ".csv"));
        if (status == JobStatus.COMPLETED) {
            jobs.attachResult(job.id(), store(StorageArea.RESULT, job.id() + ".stats.json"));
        }
        jdbc.sql("UPDATE jobs SET status = :status, finished_at = :finished WHERE id = :id")
                .param("status", status.name())
                .param("finished", finishedAgo == null ? null
                        : Instant.now().minus(finishedAgo).atOffset(ZoneOffset.UTC))
                .param("id", job.id())
                .update();
        return job;
    }

    private String store(StorageArea area, String name) {
        return storage.store(area, name, new ByteArrayInputStream("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)))
                .reference();
    }

    private boolean inputExists(Job job) {
        return storage.exists("inputs/" + job.id() + ".csv");
    }

    private boolean resultExists(Job job) {
        return storage.exists("results/" + job.id() + ".stats.json");
    }

    @Test
    void completedInputIsDeletedAfterItsRetentionAndNotBefore() {
        Job recent = job(JobStatus.COMPLETED, Duration.ofMinutes(10));
        Job old = job(JobStatus.COMPLETED, Duration.ofHours(2));

        cleaner.cleanOnce();

        assertThat(inputExists(recent)).isTrue();
        assertThat(jobs.findInputRef(recent.id())).isPresent();
        assertThat(inputExists(old)).isFalse();
        assertThat(jobs.findInputRef(old.id())).isEmpty();
        // El resultado sigue disponible: solo se limpió la entrada.
        assertThat(resultExists(old)).isTrue();
        assertThat(jobs.findResultRef(old.id())).isPresent();
    }

    @Test
    void failedInputIsKeptForManualRetriesUntilItsLongerRetention() {
        Job twoDays = job(JobStatus.FAILED, Duration.ofDays(2));
        Job eightDays = job(JobStatus.FAILED, Duration.ofDays(8));

        cleaner.cleanOnce();

        assertThat(inputExists(twoDays)).isTrue();
        assertThat(jobs.findInputRef(twoDays.id())).isPresent();
        assertThat(inputExists(eightDays)).isFalse();
        assertThat(jobs.findInputRef(eightDays.id())).isEmpty();
    }

    @Test
    void resultIsDeletedAfterItsRetention() {
        Job recent = job(JobStatus.COMPLETED, Duration.ofDays(5));
        Job old = job(JobStatus.COMPLETED, Duration.ofDays(31));

        cleaner.cleanOnce();

        assertThat(resultExists(recent)).isTrue();
        assertThat(jobs.findResultRef(recent.id())).isPresent();
        assertThat(resultExists(old)).isFalse();
        assertThat(jobs.findResultRef(old.id())).isEmpty();
    }

    @Test
    void jobsThatAreNotFinishedAreNeverTouched() {
        for (JobStatus status : new JobStatus[] { JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.RETRYING }) {
            Job job = job(status, Duration.ofDays(400));

            cleaner.cleanOnce();

            assertThat(inputExists(job)).as(status.name()).isTrue();
            assertThat(jobs.findInputRef(job.id())).as(status.name()).isPresent();
        }
    }

    @Test
    void staleTemporariesAreRemovedAndRecentOnesKept() throws IOException {
        Path inputs = storageDirectory.toAbsolutePath().resolve("inputs");
        Path stale = Files.writeString(inputs.resolve(".tmp-" + UUID.randomUUID()), "x");
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        Path fresh = Files.writeString(inputs.resolve(".tmp-" + UUID.randomUUID()), "x");

        cleaner.cleanOnce();

        assertThat(stale).doesNotExist();
        assertThat(fresh).exists();
        Files.deleteIfExists(fresh);
    }

    @Test
    void orphanFilesAreRemovedOnlyOnceOldEnoughAndNeverIfAJobPointsToThem() throws IOException {
        String oldOrphan = store(StorageArea.INPUT, UUID.randomUUID() + ".csv");
        String freshOrphan = store(StorageArea.INPUT, UUID.randomUUID() + ".csv");
        Files.setLastModifiedTime(storageDirectory.toAbsolutePath().resolve(oldOrphan),
                FileTime.from(Instant.now().minus(Duration.ofHours(3))));
        // Un fichero viejo pero con trabajo (aún pendiente) no es un huérfano.
        Job pending = job(JobStatus.QUEUED, null);
        Files.setLastModifiedTime(storageDirectory.toAbsolutePath().resolve("inputs/" + pending.id() + ".csv"),
                FileTime.from(Instant.now().minus(Duration.ofHours(3))));

        cleaner.cleanOnce();

        assertThat(storage.exists(oldOrphan)).isFalse();
        assertThat(storage.exists(freshOrphan)).isTrue();
        assertThat(inputExists(pending)).isTrue();
        storage.delete(freshOrphan);
    }

    @Test
    void aSecondPassHasNothingLeftToDo() {
        job(JobStatus.COMPLETED, Duration.ofDays(31));

        assertThat(cleaner.cleanOnce()).isEqualTo(2);
        assertThat(cleaner.cleanOnce()).isZero();
    }
}
