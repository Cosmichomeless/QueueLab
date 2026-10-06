package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.queuelab.api.metrics.QueueMetrics;
import com.queuelab.api.outbox.OutboxDispatcher;
import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.api.support.RabbitTestConfiguration;
import com.queuelab.core.job.JobRepository;
import com.queuelab.core.messaging.JobMessagingTopology;

/** Métricas de estado de la cola en {@code /actuator/prometheus}, contra PostgreSQL y RabbitMQ reales. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class QueueMetricsTest {

    private static final String QUEUED_QUEUE = JobMessagingTopology.QUEUE;

    @Autowired
    MockMvc mvc;

    @Autowired
    QueueMetrics metrics;

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitAdmin admin;

    @MockitoSpyBean
    JobRepository jobs;

    @BeforeEach
    void clean() {
        org.mockito.Mockito.reset(jobs);
        jdbc.sql("DELETE FROM jobs").update();
        admin.initialize();
        admin.purgeQueue(JobMessagingTopology.QUEUE);
        admin.purgeQueue(JobMessagingTopology.DEAD_LETTER_QUEUE);
    }

    private UUID submit() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"noop\"}"))
                .andExpect(status().isCreated());
        return jdbc.sql("SELECT id FROM jobs ORDER BY created_at DESC LIMIT 1").query(UUID.class).single();
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Valor de la serie {@code name} cuya línea contiene todas las etiquetas dadas ({@code NaN} si vale NaN). */
    private static double value(String body, String name, String... labels) {
        Pattern line = Pattern.compile("^" + Pattern.quote(name) + "\\{([^}]*)}\\s+(\\S+)$");
        return body.lines().map(line::matcher).filter(Matcher::matches)
                .filter(m -> java.util.Arrays.stream(labels).allMatch(l -> m.group(1).contains(l)))
                .map(m -> Double.parseDouble(m.group(2)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sin serie " + name + " con " + java.util.List.of(labels)));
    }

    @Test
    void jobsAreCountedByEveryStatusEvenWhenZero() throws Exception {
        submit();
        submit();
        UUID failed = submit();
        jdbc.sql("UPDATE jobs SET status = 'FAILED', finished_at = now(), error = 'x' WHERE id = :id")
                .param("id", failed).update();

        metrics.refresh();
        String body = scrape();

        assertThat(value(body, "queuelab_jobs", "status=\"queued\"")).isEqualTo(2);
        assertThat(value(body, "queuelab_jobs", "status=\"failed\"")).isEqualTo(1);
        assertThat(value(body, "queuelab_jobs", "status=\"running\"")).isZero();
        assertThat(value(body, "queuelab_jobs", "status=\"completed\"")).isZero();
        assertThat(value(body, "queuelab_jobs", "status=\"retrying\"")).isZero();
    }

    @Test
    void outboxBacklogDrainsWhenTheDispatcherPublishesAndTheBrokerQueueFills() throws Exception {
        submit();
        submit();
        submit();

        metrics.refresh();
        String before = scrape();
        assertThat(value(before, "queuelab_outbox_pending")).isEqualTo(3);
        assertThat(value(before, "queuelab_outbox_oldest_pending_age_seconds")).isGreaterThanOrEqualTo(0);
        assertThat(value(before, "queuelab_queue_messages", "queue=\"" + QUEUED_QUEUE + "\"")).isZero();

        assertThat(dispatcher.dispatchPending()).isEqualTo(3);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            metrics.refresh();
            String after = scrape();
            assertThat(value(after, "queuelab_outbox_pending")).isZero();
            assertThat(value(after, "queuelab_outbox_oldest_pending_age_seconds")).isZero();
            assertThat(value(after, "queuelab_queue_messages", "queue=\"" + QUEUED_QUEUE + "\"")).isEqualTo(3);
            assertThat(value(after, "queuelab_queue_messages",
                    "queue=\"" + JobMessagingTopology.DEAD_LETTER_QUEUE + "\"")).isZero();
            assertThat(value(after, "queuelab_queue_consumers", "queue=\"" + QUEUED_QUEUE + "\"")).isZero();
        });
    }

    @Test
    void aSourceThatFailsReportsNaNInsteadOfAStaleValue() throws Exception {
        submit();
        metrics.refresh();
        assertThat(value(scrape(), "queuelab_jobs", "status=\"queued\"")).isEqualTo(1);

        doThrow(new IllegalStateException("base de datos caída")).when(jobs).countByStatus();
        metrics.refresh();

        assertThat(value(scrape(), "queuelab_jobs", "status=\"queued\"")).isNaN();
        // Las otras fuentes siguen respondiendo.
        assertThat(value(scrape(), "queuelab_outbox_pending")).isEqualTo(1);
    }

    @Test
    void labelsComeFromAClosedSetAndNeverIncludeIds() throws Exception {
        UUID id = submit();
        metrics.refresh();
        String body = scrape();

        var labelNames = Pattern.compile("(\\w+)=\"");
        var allowed = java.util.Set.of("application", "status", "queue");
        body.lines().filter(l -> l.startsWith("queuelab_jobs") || l.startsWith("queuelab_outbox")
                        || l.startsWith("queuelab_queue"))
                .filter(l -> !l.startsWith("#"))
                .forEach(l -> {
                    Matcher m = labelNames.matcher(l.substring(0, l.lastIndexOf(' ')));
                    while (m.find()) {
                        assertThat(allowed).as("etiqueta en %s", l).contains(m.group(1));
                    }
                });
        assertThat(body).doesNotContain(id.toString());
        assertThat(body).contains("application=\"queuelab-api\"");
    }

    @Test
    void rejectedSubmissionsStayVisibleThroughTheHttpServerMetrics() throws Exception {
        mvc.perform(post("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"nope\"}"))
                .andExpect(status().isBadRequest());

        assertThat(scrape()).contains("http_server_requests_seconds_count")
                .containsPattern("status=\"400\"");
    }
}
