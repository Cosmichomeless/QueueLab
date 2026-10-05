package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.queuelab.api.support.PostgresTestConfiguration;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class FlywayMigrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void emptyDatabaseAppliesInitialMigrationOnStartup() {
        var applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);

        assertThat(applied).startsWith("1");
        assertThat(flyway.info().current().getVersion().getVersion()).isNotNull();
    }

    @Test
    void secondStartDoesNotRepeatMigrations() {
        int before = historyRows();

        // Un nuevo arranque ejecuta Flyway otra vez sobre la misma base.
        var result = flyway.migrate();

        assertThat(result.migrationsExecuted).isZero();
        assertThat(historyRows()).isEqualTo(before);
    }

    private int historyRows() {
        return jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class);
    }
}
