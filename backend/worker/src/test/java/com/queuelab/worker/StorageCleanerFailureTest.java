package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.storage.FileStorage;
import com.queuelab.core.storage.StorageArea;
import com.queuelab.core.storage.StorageException;
import com.queuelab.worker.job.StorageCleaner;
import com.queuelab.worker.support.ContainersTestConfiguration;

@SpringBootTest
@Import(ContainersTestConfiguration.class)
class StorageCleanerFailureTest {

    @Autowired
    StorageCleaner cleaner;

    @Autowired
    JobRepository jobs;

    @MockitoSpyBean
    FileStorage storage;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM jobs").update();
    }

    @Test
    void aFileThatCannotBeDeletedKeepsItsReferenceForTheNextPass() {
        Job job = Job.queued(UUID.randomUUID(), "csv-import", Instant.now());
        jobs.insert(job);
        String ref = storage.store(StorageArea.INPUT, job.id() + ".csv",
                new ByteArrayInputStream("a\n1\n".getBytes(StandardCharsets.UTF_8))).reference();
        jobs.attachInput(job.id(), ref);
        jdbc.sql("UPDATE jobs SET status = 'COMPLETED', finished_at = :t WHERE id = :id")
                .param("t", Instant.now().minusSeconds(7200).atOffset(ZoneOffset.UTC))
                .param("id", job.id())
                .update();
        doThrow(new StorageException("disco no disponible", null)).when(storage).delete(anyString());

        assertThat(cleaner.cleanOnce()).isZero();

        assertThat(jobs.findInputRef(job.id())).contains(ref);
        assertThat(storage.exists(ref)).isTrue();

        org.mockito.Mockito.reset(storage);
        assertThat(cleaner.cleanOnce()).isEqualTo(1);
        assertThat(jobs.findInputRef(job.id())).isEmpty();
        assertThat(storage.exists(ref)).isFalse();
    }
}
