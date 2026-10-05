package com.queuelab.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.queuelab.core.QueueLabCoreConfiguration;
import com.queuelab.core.messaging.JobMessagingConfiguration;

@SpringBootApplication
@Import({QueueLabCoreConfiguration.class, JobMessagingConfiguration.class})
public class QueueLabWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(QueueLabWorkerApplication.class, args);
    }
}
