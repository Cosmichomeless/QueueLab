package com.queuelab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.queuelab.worker.job.RetryPolicy;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(4, Duration.ofSeconds(5), 2.0, Duration.ofSeconds(30));

    @Test
    void delayGrowsExponentiallyAndIsCapped() {
        assertThat(policy.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayAfter(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayAfter(4)).isEqualTo(Duration.ofSeconds(30)); // 40 s acotado
        assertThat(policy.delayAfter(500)).isEqualTo(Duration.ofSeconds(30)); // sin desbordar
    }

    @Test
    void attemptsAreBounded() {
        assertThat(policy.hasAttemptsLeft(1)).isTrue();
        assertThat(policy.hasAttemptsLeft(3)).isTrue();
        assertThat(policy.hasAttemptsLeft(4)).isFalse();
        assertThat(new RetryPolicy(1, Duration.ZERO, 1.0, Duration.ZERO).hasAttemptsLeft(1)).isFalse();
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(1), 0.5, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(10), 2.0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofSeconds(-1), 2.0, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
