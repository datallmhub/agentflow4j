package io.github.datallmhub.agentflow4j.graph;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphStreamGovernanceTests {

    private static final StateKey<Boolean> CONFIRMED = StateKey.of("payment.confirmed", Boolean.class);

    @Test
    void approvalGateInterruptsStreamBeforeFlaggedNode() {
        AtomicInteger guardedCalls = new AtomicInteger();
        Agent guarded = ctx -> {
            guardedCalls.incrementAndGet();
            return AgentResult.ofText("transferred");
        };

        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", guarded)
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(store)
                .build();

        AgentResult result = finalResult(graph.invokeStream(AgentContext.of("transfer 500"), RunOptions.ofRunId("run-s1")));

        assertThat(guardedCalls.get()).isZero();
        assertThat(result.isInterrupted()).isTrue();
        assertThat(result.interrupt().reason()).isEqualTo("approval.required:payment.transfer");
        assertThat(store.load("run-s1")).isPresent();
    }

    @Test
    void streamedRunPausedForApprovalCanBeResumed() {
        AtomicInteger guardedCalls = new AtomicInteger();
        Agent guarded = ctx -> {
            guardedCalls.incrementAndGet();
            return AgentResult.ofText("transferred");
        };

        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", guarded)
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(store)
                .build();

        finalResult(graph.invokeStream(AgentContext.of("transfer 500"), RunOptions.ofRunId("run-s2")));
        AgentResult resumed = graph.resume("run-s2", ResumeOptions.ofApproval("payment.transfer"));

        assertThat(resumed.completed()).isTrue();
        assertThat(resumed.text()).isEqualTo("transferred");
        assertThat(guardedCalls.get()).isEqualTo(1);
        assertThat(store.load("run-s2")).isEmpty();
    }

    @Test
    void approvalGateAppliesToStreamWithoutRunId() {
        AtomicInteger guardedCalls = new AtomicInteger();
        Agent guarded = ctx -> {
            guardedCalls.incrementAndGet();
            return AgentResult.ofText("transferred");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", guarded)
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .build();

        AgentResult result = finalResult(graph.invokeStream(AgentContext.of("transfer 500")));

        assertThat(guardedCalls.get()).isZero();
        assertThat(result.isInterrupted()).isTrue();
    }

    @Test
    void statePolicyDeniesWriteDuringStream() {
        Agent sneaky = ctx -> AgentResult.builder()
                .text("set confirmed")
                .stateUpdates(Map.of(CONFIRMED, true))
                .completed(true)
                .build();

        AgentGraph graph = AgentGraph.builder()
                .addNode("sneaky", sneaky)
                .statePolicy(StatePolicy.denyWriteKeys("payment.confirmed"))
                .build();

        AgentResult result = finalResult(graph.invokeStream(AgentContext.of("go")));

        assertThat(result.hasError()).isTrue();
        assertThat(result.error().cause()).isInstanceOf(StatePolicyViolation.class);
    }

    @Test
    void interruptedNodeStopsStreamBeforeNextNode() {
        AtomicInteger nextCalls = new AtomicInteger();
        Agent pausing = ctx -> AgentResult.interrupted("waiting for input");
        Agent next = ctx -> {
            nextCalls.incrementAndGet();
            return AgentResult.ofText("next");
        };

        InMemoryRunLogStore runLog = new InMemoryRunLogStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("pausing", pausing)
                .addNode("next", next)
                .addEdge("pausing", "next")
                .runLog(runLog)
                .build();

        AgentResult result = finalResult(graph.invokeStream(AgentContext.of("go"), RunOptions.ofRunId("run-s3")));

        assertThat(nextCalls.get()).isZero();
        assertThat(result.isInterrupted()).isTrue();
        assertThat(graph.runLog("run-s3")).isNotEmpty();
    }

    @Test
    void streamFailsBeforeNextNodeOnceTimeoutExceeded() {
        Agent slow = ctx -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            return AgentResult.ofText("slow");
        };
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", slow)
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .build();

        AgentResult result = finalResult(graph.invokeStream(AgentContext.of("go"),
                RunOptions.ofTimeout(java.time.Duration.ofMillis(50))));

        assertThat(result.hasError()).isTrue();
        assertThat(result.error().cause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    private static AgentResult finalResult(reactor.core.publisher.Flux<AgentEvent> stream) {
        AgentEvent last = stream.blockLast();
        assertThat(last).isInstanceOf(AgentEvent.Completed.class);
        return ((AgentEvent.Completed) last).result();
    }
}
