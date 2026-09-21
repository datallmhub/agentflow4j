package io.github.datallmhub.agentflow4j.graph;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphRetryTests {

    @Test
    void nodeFailsTwiceThenSucceedsWithMaxAttemptsThree() {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new java.io.UncheckedIOException(new IOException("transient boom " + n));
            }
            return AgentResult.ofText("ok");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("flaky", flaky)
                .retryPolicy(new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                        t -> t instanceof java.io.UncheckedIOException
                                ? FailureClassification.transientFailure() : null))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));
        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void retryPredicateShortCircuitsOnNonTransientError() {
        AtomicInteger calls = new AtomicInteger();
        Agent broken = ctx -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("permanent");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("broken", broken)
                .retryPolicy(new RetryPolicy(5, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                        FailureClassifier.defaults()))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));
        assertThat(result.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void maxAttemptsOneMeansNoRetry() {
        AtomicInteger calls = new AtomicInteger();
        Agent failing = ctx -> {
            calls.incrementAndGet();
            throw new RuntimeException("nope");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("f", failing)
                .retryPolicy(RetryPolicy.none())
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));
        assertThat(result.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void skipNodePolicyKicksInAfterRetriesExhausted() {
        AtomicInteger calls = new AtomicInteger();
        Agent failing = ctx -> {
            calls.incrementAndGet();
            throw new RuntimeException("always fails");
        };
        Agent after = ctx -> AgentResult.ofText("recovered");

        AgentGraph graph = AgentGraph.builder()
                .addNode("f", failing)
                .addNode("after", after)
                .addEdge("f", "after")
                .errorPolicy(ErrorPolicy.SKIP_NODE)
                .retryPolicy(new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                        FailureClassifier.defaults().orElse(FailureClassifier.alwaysTransient())))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));
        assertThat(result.text()).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void perNodeRetryPolicyOverridesGraphDefault() {
        AtomicInteger calls = new AtomicInteger();
        Agent flaky = ctx -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new java.io.UncheckedIOException(new IOException("boom " + n));
            }
            return AgentResult.ofText("ok");
        };

        RetryPolicy nodeRetry = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                FailureClassifier.defaults().orElse(FailureClassifier.alwaysTransient()));
        AgentGraph graph = AgentGraph.builder()
                .addNode("flaky", flaky, nodeRetry)
                .retryPolicy(RetryPolicy.none())
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));
        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void perNodeNoneDisablesRetryWhileOtherNodesKeepGraphDefault() {
        AtomicInteger protectedCalls = new AtomicInteger();
        AtomicInteger strictCalls = new AtomicInteger();
        Agent alwaysFailStrict = ctx -> {
            strictCalls.incrementAndGet();
            throw new RuntimeException("strict never retries");
        };
        Agent alwaysFailProtected = ctx -> {
            protectedCalls.incrementAndGet();
            throw new RuntimeException("protected retries 3x");
        };

        RetryPolicy graphDefault = new RetryPolicy(3, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                FailureClassifier.defaults().orElse(FailureClassifier.alwaysTransient()));

        AgentGraph strictFirst = AgentGraph.builder()
                .addNode("strict", alwaysFailStrict, RetryPolicy.none())
                .retryPolicy(graphDefault)
                .build();
        strictFirst.invoke(AgentContext.of("go"));
        assertThat(strictCalls.get()).isEqualTo(1);

        AgentGraph defaultOnly = AgentGraph.builder()
                .addNode("plain", alwaysFailProtected)
                .retryPolicy(graphDefault)
                .build();
        defaultOnly.invoke(AgentContext.of("go"));
        assertThat(protectedCalls.get()).isEqualTo(3);
    }
}
