package io.github.datallmhub.agentflow4j.graph;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the reason-aware retry layer at the graph level:
 * OVER_BUDGET produces an {@link io.github.datallmhub.agentflow4j.core.InterruptRequest}
 * rather than retrying; PERMANENT short-circuits the retry loop; TRANSIENT
 * retries up to {@code maxAttempts}; the {@code Retry-After} hint takes
 * precedence over the policy's computed backoff.
 */
class AgentGraphReasonAwareRetryTests {

    @Test
    void budgetExceededExceptionInterruptsRunInsteadOfRetrying() {
        AtomicInteger calls = new AtomicInteger();
        Agent overBudget = ctx -> {
            calls.incrementAndGet();
            throw new BudgetExceededException("monthly cap reached");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("billed", overBudget)
                .retryPolicy(RetryPolicy.exponential(5, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.isInterrupted()).isTrue();
        assertThat(result.interrupt()).isNotNull();
        assertThat(result.interrupt().reason()).isEqualTo("budget.exceeded:billed");
        assertThat(calls.get()).isEqualTo(1); // no retry on budget exhaustion
    }

    @Test
    void permanentFailureIsNotRetriedUnderDefaultClassifier() {
        AtomicInteger calls = new AtomicInteger();
        Agent broken = ctx -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("permanent");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("broken", broken)
                .retryPolicy(RetryPolicy.exponential(5, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void transientIoFailureIsRetriedUnderDefaultClassifier() {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new java.io.UncheckedIOException(new IOException("transient " + n));
            }
            return AgentResult.ofText("ok");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("flaky", flaky)
                .retryPolicy(RetryPolicy.exponential(3, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void springClientErrorOtherThan429IsTreatedAsPermanent() {
        AtomicInteger calls = new AtomicInteger();
        Agent broken = ctx -> {
            calls.incrementAndGet();
            throw new org.springframework.web.client.HttpClientErrorException.BadRequest("400");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("broken", broken)
                .retryPolicy(RetryPolicy.exponential(5, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(1); // 4xx → no retry
    }

    @Test
    void springTooManyRequestsIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = calls.incrementAndGet();
            if (n < 2) {
                throw new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
            }
            return AgentResult.ofText("ok");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("ratelimited", flaky)
                .retryPolicy(RetryPolicy.exponential(3, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.completed()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void retryAfterHeaderTakesPrecedenceOverPolicyBackoff() {
        // Policy would compute a 10-second backoff; Retry-After: 0 must win and
        // make the retry essentially immediate. We assert via wall-clock that
        // the call completed in well under the policy backoff.
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = calls.incrementAndGet();
            if (n < 2) {
                org.springframework.web.client.HttpClientErrorException.TooManyRequests t =
                        new org.springframework.web.client.HttpClientErrorException.TooManyRequests("429");
                t.withHeader("Retry-After", "0");
                throw t;
            }
            return AgentResult.ofText("ok");
        };

        // Policy backoff: 10 seconds. Retry-After: 0 must override → run < 1s.
        RetryPolicy policy = new RetryPolicy(3,
                Duration.ofSeconds(10), Duration.ofSeconds(10),
                1.0, 0.0,
                RetryPredicates.always());

        AgentGraph graph = AgentGraph.builder()
                .addNode("ratelimited", flaky)
                .retryPolicy(policy)
                .build();

        long t0 = System.nanoTime();
        AgentResult result = graph.invoke(AgentContext.of("go"));
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(result.completed()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(elapsedMs)
                .as("Retry-After:0 must override the 10s policy backoff")
                .isLessThan(1_000);
    }

    @Test
    void customClassifierOverridesDefault() {
        AtomicInteger calls = new AtomicInteger();
        Agent broken = ctx -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("would be permanent under default");
        };

        // Custom classifier treats IllegalArgumentException as TRANSIENT
        FailureClassifier custom = cause ->
                cause instanceof IllegalArgumentException
                        ? FailureClassification.transientFailure()
                        : null;

        RetryPolicy policy = RetryPolicy.exponential(3, Duration.ZERO)
                .withClassifier(custom.orElse(FailureClassifier.defaults()));

        AgentGraph graph = AgentGraph.builder()
                .addNode("broken", broken)
                .retryPolicy(policy)
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(3); // custom classifier made it retry
    }

    @Test
    void budgetExceededWrappedInRuntimeExceptionStillInterrupts() {
        Agent overBudget = ctx -> {
            throw new RuntimeException("wrapper",
                    new BudgetExceededException("quota burned"));
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("billed", overBudget)
                .retryPolicy(RetryPolicy.exponential(5, Duration.ZERO))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.isInterrupted()).isTrue();
        assertThat(result.interrupt().reason()).isEqualTo("budget.exceeded:billed");
    }
}
