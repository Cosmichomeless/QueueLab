package com.queuelab.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** Las URLs y credenciales de los servicios se leen de variables de entorno. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "management.health.db.enabled=false",
        "QUEUELAB_DB_URL=jdbc:postgresql://db.example:6543/custom",
        "QUEUELAB_DB_USER=db-user",
        "QUEUELAB_DB_PASSWORD=db-secret",
        "QUEUELAB_RABBITMQ_HOST=mq.example",
        "QUEUELAB_RABBITMQ_PORT=5999",
        "QUEUELAB_RABBITMQ_USER=mq-user",
        "QUEUELAB_RABBITMQ_PASSWORD=mq-secret"
})
class ExternalConfigurationTest {

    @Autowired
    Environment env;

    @Test
    void databaseSettingsComeFromEnvironment() {
        assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db.example:6543/custom");
        assertThat(env.getProperty("spring.datasource.username")).isEqualTo("db-user");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("db-secret");
    }

    @Test
    void rabbitMqSettingsComeFromEnvironment() {
        assertThat(env.getProperty("spring.rabbitmq.host")).isEqualTo("mq.example");
        assertThat(env.getProperty("spring.rabbitmq.port", Integer.class)).isEqualTo(5999);
        assertThat(env.getProperty("spring.rabbitmq.username")).isEqualTo("mq-user");
        assertThat(env.getProperty("spring.rabbitmq.password")).isEqualTo("mq-secret");
    }
}
