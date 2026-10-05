package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.queuelab.api.support.PostgresTestConfiguration;
import com.queuelab.core.messaging.JobMessageCodec;

/** Migra un esquema aparte desde la V3 (sin outbox) y comprueba el rellenado de la V5. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OutboxBackfillMigrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    void queuedJobsCreatedBeforeTheOutboxGetAPendingEvent() {
        String schema = "backfill_test";
        Flyway before = Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration").target("3").load();
        before.migrate();

        JdbcClient jdbc = JdbcClient.create(dataSource);
        UUID queued = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        jdbc.sql("SET search_path TO " + schema).update();
        insert(jdbc, schema, queued, "QUEUED");
        insert(jdbc, schema, running, "RUNNING");

        Flyway.configure().dataSource(dataSource).schemas(schema)
                .locations("classpath:db/migration").load().migrate();

        var events = jdbc.sql("SELECT job_id, event_type, payload::text AS payload, published_at "
                        + "FROM " + schema + ".outbox_events")
                .query((rs, i) -> new Object[] {rs.getObject("job_id", UUID.class), rs.getString("event_type"),
                        rs.getString("payload"), rs.getObject("published_at")})
                .list();

        assertThat(events).hasSize(1);
        Object[] event = events.getFirst();
        assertThat(event[0]).isEqualTo(queued);
        assertThat(event[1]).isEqualTo("JOB_QUEUED");
        assertThat(JobMessageCodec.decode(JobMessageCodec.encodeJson((String) event[2])).jobId()).isEqualTo(queued);
        assertThat(event[3]).isNull();
    }

    private static void insert(JdbcClient jdbc, String schema, UUID id, String status) {
        jdbc.sql("INSERT INTO " + schema + ".jobs (id, type, status, created_at, updated_at) "
                        + "VALUES (:id, 'noop', :status, now(), now())")
                .param("id", id).param("status", status).update();
    }
}
