package com.queuelab.worker.job;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class LeaseConfiguration {

    @Bean
    LeasePolicy leasePolicy(@Value("${queuelab.worker.lease.duration:2m}") Duration duration) {
        return new LeasePolicy(duration);
    }

    /**
     * Scheduler propio para {@code @Scheduled} (el recuperador). Sin él, Spring usaría {@link #leaseRenewer()}
     * y una pasada de recuperación lenta retrasaría los latidos de los trabajos en curso.
     */
    @Bean
    TaskScheduler recoveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("recovery-");
        scheduler.setDaemon(true);
        return scheduler;
    }

    /** Hilo que renueva los leases de los trabajos en ejecución; se cierra con el contexto. */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService leaseRenewer() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "lease-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }
}
