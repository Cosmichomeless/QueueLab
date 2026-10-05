package com.queuelab.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.queuelab.core.messaging.JobMessagingConfiguration;

@SpringBootApplication
@Import(JobMessagingConfiguration.class)
public class QueueLabWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueLabWorkerApplication.class, args);
    }
}
