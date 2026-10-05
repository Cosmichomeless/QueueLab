package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

@SpringBootTest
class WorkerStartupTest {

    @Autowired
    ApplicationContext context;

    @Test
    void startsWithoutPublicHttpServer() {
        // El worker no incluye Spring MVC/Tomcat: no puede abrir un servidor HTTP.
        assertThat(ClassUtils.isPresent("org.springframework.web.context.WebApplicationContext",
                getClass().getClassLoader())).isFalse();
        assertThat(context.getClass().getSimpleName()).doesNotContain("Web");
    }
}
