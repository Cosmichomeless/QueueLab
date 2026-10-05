package com.queuelab.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.queuelab.core.QueueLabCoreConfiguration;
import com.queuelab.core.messaging.JobMessagingConfiguration;

@SpringBootApplication
@EnableScheduling
@Import({QueueLabCoreConfiguration.class, JobMessagingConfiguration.class})
public class QueueLabApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueLabApiApplication.class, args);
    }
}
