package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.api.support.RabbitTestConfiguration;
import com.queuelab.core.messaging.JobMessage;
import com.queuelab.core.messaging.JobMessageCodec;
import com.queuelab.core.messaging.JobMessagingTopology;

/**
 * Arranque de la API contra PostgreSQL y RabbitMQ reales y desechables (Testcontainers, sin servicios manuales):
 * Flyway aplica <em>todas</em> las migraciones del classpath y la topología de RabbitMQ aparece sola al abrir la
 * primera conexión, sin que el test llame a {@code admin.initialize()}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class RealServicesStartupTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    RabbitAdmin admin;

    @Autowired
    RabbitTemplate rabbit;

    @Test
    void flywayAppliesEveryMigrationOnTheClasspathWithNoneFailedOrPending() throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*.sql");

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(files).isNotEmpty();
        assertThat(applied).hasSize(files.length);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(Arrays.stream(applied).map(MigrationInfo::getState)).allMatch(state -> state.isApplied());
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE NOT success")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void migratedSchemaHasTheTablesTheServicesNeed() {
        List<String> tables = jdbc.sql("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'
                """).query(String.class).list();

        assertThat(tables).contains("jobs", "outbox_events", "flyway_schema_history");
    }

    @Test
    void topologyIsDeclaredOnFirstConnectionAndRoutesBothWays() {
        // Solo abrir la conexión: el RabbitAdmin de la propia API declara intercambios, colas y bindings.
        rabbit.getConnectionFactory().createConnection().close();
        admin.purgeQueue(JobMessagingTopology.QUEUE);
        admin.purgeQueue(JobMessagingTopology.DEAD_LETTER_QUEUE);

        assertThat(admin.getQueueProperties(JobMessagingTopology.QUEUE)).isNotNull();
        assertThat(admin.getQueueProperties(JobMessagingTopology.DEAD_LETTER_QUEUE)).isNotNull();

        UUID jobId = UUID.randomUUID();
        Message body = JobMessageCodec.encode(new JobMessage(JobMessage.CURRENT_VERSION, jobId));
        rabbit.send(JobMessagingTopology.EXCHANGE, JobMessagingTopology.ROUTING_KEY, body);
        rabbit.send(JobMessagingTopology.DEAD_LETTER_EXCHANGE, JobMessagingTopology.DEAD_LETTER_ROUTING_KEY, body);

        Message main = rabbit.receive(JobMessagingTopology.QUEUE, 5_000);
        Message dead = rabbit.receive(JobMessagingTopology.DEAD_LETTER_QUEUE, 5_000);
        assertThat(main).isNotNull();
        assertThat(JobMessageCodec.decode(main).jobId()).isEqualTo(jobId);
        assertThat(dead).isNotNull();
    }
}
