package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/** El perfil `local` aporta credenciales de desarrollo; sin él no hay contraseñas por defecto. */
@SpringBootTest(properties = "spring.profiles.active=local")
class LocalProfileTest {

    @Autowired
    Environment env;

    @Test
    void localProfileProvidesDevelopmentCredentials() {
        assertThat(env.getActiveProfiles()).containsExactly("local");
        assertThat(env.getProperty("spring.datasource.password")).isEqualTo("queuelab");
        assertThat(env.getProperty("spring.rabbitmq.password")).isEqualTo("queuelab");
    }
}
