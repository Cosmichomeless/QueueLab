package com.queuelab.worker.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ConsumerConcurrencyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConcurrencyConfiguration.class);

    @Test
    void defaultsAreSafe() {
        runner.run(context -> {
            assertThat(context.getBean(ConsumerConcurrency.class)).isEqualTo(new ConsumerConcurrency(2, 1));
        });
    }

    @Test
    void valuesComeFromConfiguration() {
        runner.withPropertyValues("queuelab.worker.concurrency=8", "queuelab.worker.prefetch=4").run(context -> {
            assertThat(context.getBean(ConsumerConcurrency.class)).isEqualTo(new ConsumerConcurrency(8, 4));
        });
    }

    @Test
    void zeroOrNegativeConsumersAreRejected() {
        assertThatThrownBy(() -> new ConsumerConcurrency(0, 1)).hasMessageContaining("queuelab.worker.concurrency");
        assertThatThrownBy(() -> new ConsumerConcurrency(-1, 1)).hasMessageContaining("entre 1 y 64");
    }

    @Test
    void absurdlyHighConsumerCountsAreRejected() {
        assertThatThrownBy(() -> new ConsumerConcurrency(ConsumerConcurrency.MAX_CONSUMERS + 1, 1))
                .hasMessageContaining("queuelab.worker.concurrency");
        assertThat(new ConsumerConcurrency(ConsumerConcurrency.MAX_CONSUMERS, 1).consumers()).isEqualTo(64);
    }

    @Test
    void prefetchMustBeAtLeastOne() {
        assertThatThrownBy(() -> new ConsumerConcurrency(2, 0)).hasMessageContaining("queuelab.worker.prefetch");
    }

    @Test
    void invalidConfigurationFailsContextStartup() {
        runner.withPropertyValues("queuelab.worker.concurrency=0").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "queuelab.worker.concurrency debe estar entre 1 y 64 (valor: 0)");
        });
    }
}
