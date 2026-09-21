package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphCircuitBreakerTests {

    /** Simple counting breaker: opens after {@code openAfter} calls; while open, throws. */
    private static final class TestBreaker implements CircuitBreakerPolicy {
        private final int openAfter;
        private int calls;
        private boolean open;
        int executions;
        int shortCircuits;

        TestBreaker(int openAfter) {
            this.openAfter = openAfter;
            this.open = openAfter == 0;
        }

        @Override
        public <T> T execute(String nodeName, Supplier<T> call) {
            if (open) {
                shortCircuits++;
                throw new IllegalStateException("breaker open for " + nodeName);
            }
            executions++;
            try {
                return call.get();
            } finally {
                if (++calls >= openAfter) {
                    open = true;
                }
            }
        }
    }

    @Test
    void nodeExecutesThroughBreakerOnHappyPath() {
        TestBreaker breaker = new TestBreaker(Integer.MAX_VALUE);
        Agent ok = ctx -> AgentResult.ofText("ok");

        AgentGraph graph = AgentGraph.builder()
                .addNode("n", ok, null, breaker)
                .build();

        AgentResult r = graph.invoke(AgentContext.of("go"));
        assertThat(r.completed()).isTrue();
        assertThat(breaker.executions).isEqualTo(1);
        assertThat(breaker.shortCircuits).isZero();
    }

    @Test
    void retryAttemptsIterateThroughBreakerAndHaltWhenItOpens() {
        TestBreaker breaker = new TestBreaker(2);
        AtomicInteger calls = new AtomicInteger();
        Agent failing = ctx -> {
            calls.incrementAndGet();
            throw new RuntimeException("transient");
        };

        RetryPolicy retry = new RetryPolicy(5, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                FailureClassifier.defaults().orElse(FailureClassifier.alwaysTransient()));
        AgentGraph graph = AgentGraph.builder()
                .addNode("flaky", failing, retry, breaker)
                .build();

        AgentResult r = graph.invoke(AgentContext.of("go"));

        assertThat(r.hasError()).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(breaker.executions).isEqualTo(2);
        assertThat(breaker.shortCircuits).isEqualTo(3);
    }

    @Test
    void breakerOpenErrorFlowsThroughSkipNodePolicy() {
        TestBreaker breaker = new TestBreaker(0);
        Agent next = ctx -> AgentResult.ofText("recovered");

        AgentGraph graph = AgentGraph.builder()
                .addNode("blocked", ctx -> AgentResult.ofText("never runs"), null, breaker)
                .addNode("after", next)
                .addEdge("blocked", "after")
                .errorPolicy(ErrorPolicy.SKIP_NODE)
                .build();

        AgentResult r = graph.invoke(AgentContext.of("go"));

        assertThat(r.text()).isEqualTo("recovered");
        assertThat(breaker.shortCircuits).isEqualTo(1);
    }
}
