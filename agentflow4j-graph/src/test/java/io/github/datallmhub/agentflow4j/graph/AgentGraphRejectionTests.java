package io.github.datallmhub.agentflow4j.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphRejectionTests {

    private static final StateKey<String> PLAN = StateKey.of("plan", String.class);


    /** "charge" stands for a node with a side effect that must never run twice. */
    private AgentGraph graph(List<String> calls, CheckpointStore store, RunLogStore runLog) {
        return AgentGraph.builder()
                .addNode("plan", ctx -> {
                    calls.add("plan");
                    return AgentResult.builder().stateUpdates(Map.of(PLAN, "v1")).completed(true).build();
                })
                .addNode("charge", ctx -> {
                    calls.add("charge");
                    return AgentResult.ofText("charged");
                })
                .addNode("payment.transfer", ctx -> {
                    calls.add("payment.transfer");
                    return AgentResult.ofText("transferred");
                })
                .addEdge("plan", "charge")
                .addEdge("charge", "payment.transfer")
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                // A rejection sends the run back to the top; the memo keeps
                // "plan" and "charge" from running a second time.
                .onRejection("payment.transfer", "plan")
                .checkpointStore(store)
                .runLog(runLog)
                .build();
    }

    @Test
    void rejectionReroutesWithoutReplayingSideEffects() {
        List<String> calls = new CopyOnWriteArrayList<>();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        InMemoryRunLogStore runLog = new InMemoryRunLogStore();
        AgentGraph graph = graph(calls, store, runLog);

        AgentResult paused = graph.invoke(AgentContext.of("pay"), RunOptions.ofRunId("run-r"));
        assertThat(paused.isInterrupted()).isTrue();
        assertThat(calls).containsExactly("plan", "charge");

        // The operator rejects: the run restarts at "plan", which already ran,
        // so neither "plan" nor the side-effecting "charge" runs again.
        AgentResult afterRejection = graph.resume("run-r",
                ResumeOptions.ofRejection("payment.transfer", "amount too high"));

        assertThat(afterRejection.isInterrupted()).isTrue();
        assertThat(calls).containsExactly("plan", "charge");
        assertThat(graph.runLog("run-r").stream()
                .filter(e -> e.type() == RunEventType.NODE_SKIPPED)
                .map(AgentRunEvent::node))
                .containsExactly("plan", "charge");

        // Approving then runs the gated node exactly once, still without replaying "charge".
        AgentResult approved = graph.resume("run-r", ResumeOptions.ofApproval("payment.transfer"));
        assertThat(approved.text()).isEqualTo("transferred");
        assertThat(calls).containsExactly("plan", "charge", "payment.transfer");
    }

    @Test
    void invalidatingANodeMakesItRunAgain() {
        List<String> calls = new CopyOnWriteArrayList<>();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = graph(calls, store, new InMemoryRunLogStore());

        graph.invoke(AgentContext.of("pay"), RunOptions.ofRunId("run-i"));
        graph.resume("run-i", ResumeOptions.ofRejection("payment.transfer", "wrong amount")
                .withInvalidated("plan")
                .withInvalidated("charge"));

        assertThat(calls).containsExactly("plan", "charge", "plan", "charge");
    }

    @Test
    void rejectionWithoutARouteEndsTheRun() {
        List<String> calls = new CopyOnWriteArrayList<>();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", ctx -> {
                    calls.add("payment.transfer");
                    return AgentResult.ofText("transferred");
                })
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(store)
                .build();

        graph.invoke(AgentContext.of("pay"), RunOptions.ofRunId("run-x"));
        AgentResult rejected = graph.resume("run-x",
                ResumeOptions.ofRejection("payment.transfer", "not allowed"));

        assertThat(rejected.isInterrupted()).isTrue();
        assertThat(rejected.interrupt().reason()).isEqualTo("approval.rejected:payment.transfer");
        assertThat(calls).isEmpty();
        assertThat(store.load("run-x")).isEmpty();
    }

    @Test
    void builderRejectsARouteToAnUnknownNode() {
        try {
            AgentGraph.builder()
                    .addNode("a", ctx -> AgentResult.ofText("a"))
                    .onRejection("a", "ghost")
                    .build();
            assertThat(false).as("expected IllegalStateException").isTrue();
        }
        catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("ghost");
        }
    }
}
