package io.github.datallmhub.agentflow4j.graph;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryPolicyTests {

    @Test
    void noneHasSingleAttemptAndNeverRetries() {
        RetryPolicy p = RetryPolicy.none();
        assertThat(p.maxAttempts()).isEqualTo(1);
        assertThat(p.classify(new RuntimeException()).category()).isEqualTo(FailureCategory.PERMANENT);
    }

    @Test
    void onceAllowsOneRetryOnAnything() {
        RetryPolicy p = RetryPolicy.once();
        assertThat(p.maxAttempts()).isEqualTo(2);
        assertThat(p.classify(new RuntimeException("any")).category()).isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void exponentialDefaultsRetryOnTransientIoOnly() {
        RetryPolicy p = RetryPolicy.exponential(3, Duration.ofMillis(100));
        assertThat(p.maxAttempts()).isEqualTo(3);
        assertThat(p.classify(new IOException()).category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(p.classify(new TimeoutException()).category()).isEqualTo(FailureCategory.TRANSIENT);
        assertThat(p.classify(new IllegalArgumentException()).category()).isEqualTo(FailureCategory.PERMANENT);
    }

    @Test
    void computeDelayGrowsExponentiallyWithoutJitter() {
        RetryPolicy p = new RetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(10),
                2.0, 0.0, FailureClassifier.defaults());
        assertThat(p.computeDelayMs(1)).isEqualTo(100);
        assertThat(p.computeDelayMs(2)).isEqualTo(200);
        assertThat(p.computeDelayMs(3)).isEqualTo(400);
        assertThat(p.computeDelayMs(4)).isEqualTo(800);
    }

    @Test
    void computeDelayRespectsMaxDelayCap() {
        RetryPolicy p = new RetryPolicy(10, Duration.ofMillis(100), Duration.ofMillis(500),
                2.0, 0.0, FailureClassifier.defaults());
        assertThat(p.computeDelayMs(1)).isEqualTo(100);
        assertThat(p.computeDelayMs(3)).isEqualTo(400);
        assertThat(p.computeDelayMs(4)).isEqualTo(500);
        assertThat(p.computeDelayMs(8)).isEqualTo(500);
    }

    @Test
    void jitterBoundsNeverExceedCapAndNeverGoBelowFloor() {
        RetryPolicy p = new RetryPolicy(3, Duration.ofMillis(1000), Duration.ofMillis(1000),
                1.0, 0.5, FailureClassifier.defaults());
        for (int i = 0; i < 100; i++) {
            long d = p.computeDelayMs(1);
            assertThat(d).isBetween(500L, 1000L);
        }
    }

    @Test
    void jitterFactorOneProducesFullRange() {
        RetryPolicy p = new RetryPolicy(3, Duration.ofMillis(1000), Duration.ofMillis(1000),
                1.0, 1.0, FailureClassifier.defaults());
        for (int i = 0; i < 100; i++) {
            long d = p.computeDelayMs(1);
            assertThat(d).isBetween(0L, 1000L);
        }
    }

    @Test
    void validationRejectsInvalidInputs() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, 1.0, 0.0, FailureClassifier.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(2, Duration.ofSeconds(2), Duration.ofSeconds(1), 1.0, 0.0, FailureClassifier.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(1), 0.5, 0.0, FailureClassifier.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(2, Duration.ofMillis(1), Duration.ofMillis(1), 1.0, 1.5, FailureClassifier.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onceKeepsCategoriesRecognisedByDefaultClassifier() {
        RetryPolicy p = RetryPolicy.once();
        assertThat(p.classify(new BudgetExceededException("cap")).category())
                .isEqualTo(FailureCategory.OVER_BUDGET);
    }
}
