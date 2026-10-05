package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.queuelab.core.job.Job;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.job.JobStatus;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.worker.job.JobExecutor;
import com.queuelab.worker.job.JobProcessor;
import com.queuelab.worker.support.ContainersTestConfiguration;

/** Varios workers (hilos con su propia conexión) compiten por el mismo trabajo contra PostgreSQL real. */
@SpringBootTest
@Import(ContainersTestConfiguration.class)
class AtomicClaimTest {

    static final int WORKERS = 12;

    @Autowired
    JobRepository jobs;

    @Autowired
    JobProcessor processor;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    JobExecutor executor;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(executor);
        jdbc.sql("DELETE FROM jobs").update();
    }

    private Job storedJob() {
        Job job = Job.queued(UUID.randomUUID(), "noop", Instant.now());
        jobs.insert(job);
        return job;
    }

    private <T> List<T> race(Callable<T> task) throws Exception {
        var pool = Executors.newFixedThreadPool(WORKERS);
        var ready = new CountDownLatch(WORKERS);
        var go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = IntStream.range(0, WORKERS).mapToObj(i -> pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            })).toList();
            ready.await();
            go.countDown();
            var results = new java.util.ArrayList<T>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyOneOfManyConcurrentClaimsWins() throws Exception {
        Job job = storedJob();

        var results = race(() -> jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)));

        assertThat(results.stream().filter(java.util.Optional::isPresent)).hasSize(1);
        Job stored = jobs.findById(job.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(stored.attempts()).isEqualTo(1);
        assertThat(stored.startedAt()).isNotNull();
    }

    @Test
    void claimOnlyAppliesToQueuedJobs() {
        Job job = storedJob();
        Job claimed = jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);

        assertThat(jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60))).isEmpty(); // ya RUNNING
        jobs.finishAttempt(claimed.completed("ok", Instant.now()));
        assertThat(jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60))).isEmpty(); // ya COMPLETED
        assertThat(jobs.claim(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60))).isEmpty(); // inexistente
        assertThat(jobs.findById(job.id()).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfTheSameMessageExecuteTheJobOnce() throws Exception {
        Job job = storedJob();
        var executions = new AtomicInteger();
        doAnswer(invocation -> {
            executions.incrementAndGet();
            Thread.sleep(300); // ventana amplia para que los demás workers intenten entrar
            return "ok";
        }).when(executor).execute(any());

        race(() -> {
            processor.process(JobMessage.forJob(job.id()));
            return null;
        });

        assertThat(executions).hasValue(1);
        verify(executor, times(1)).execute(any());
        Job stored = jobs.findById(job.id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(stored.attempts()).isEqualTo(1);
        assertThat(stored.result()).isEqualTo("ok");
    }

    @Test
    void redeliveryAfterCompletionIsAcknowledgedWithoutRepeatingEffects() {
        Job job = storedJob();
        processor.process(JobMessage.forJob(job.id()));
        Job done = jobs.findById(job.id()).orElseThrow();

        processor.process(JobMessage.forJob(job.id())); // duplicado: no lanza, no ejecuta

        verify(executor, times(1)).execute(any());
        assertThat(jobs.findById(job.id()).orElseThrow()).isEqualTo(done);
    }

    @Test
    void staleAttemptCannotOverwriteANewerOne() {
        Job job = storedJob();
        Job attempt1 = jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        // Simula que el intento 1 fue relevado: vuelve a QUEUED y otro worker reclama el intento 2.
        jdbc.sql("UPDATE jobs SET status = 'QUEUED' WHERE id = :id").param("id", job.id()).update();
        Job attempt2 = jobs.claim(job.id(), Instant.now(), Instant.now().plusSeconds(60)).orElseThrow();
        assertThat(attempt2.attempts()).isEqualTo(2);

        assertThat(jobs.finishAttempt(attempt1.completed("viejo", Instant.now()))).isFalse();
        assertThat(jobs.findById(job.id()).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

        assertThat(jobs.finishAttempt(attempt2.completed("nuevo", Instant.now()))).isTrue();
        assertThat(jobs.findById(job.id()).orElseThrow().result()).isEqualTo("nuevo");
    }
}
