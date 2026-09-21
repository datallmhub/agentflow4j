package io.github.datallmhub.agentflow4j.graph;

import java.util.List;
import java.util.Map;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunLogTests {

    @Test
    void recordsEnterExitTransitionAndCompleteInOrder() {
        Agent a = ctx -> AgentResult.ofText("a");
        Agent b = ctx -> AgentResult.ofText("b");

        InMemoryRunLogStore store = new InMemoryRunLogStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", a)
                .addNode("b", b)
                .addEdge("a", "b")
                .runLog(store)
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-1"));

        List<AgentRunEvent> events = graph.runLog("run-1");
        List<RunEventType> types = events.stream().map(AgentRunEvent::type).toList();

        assertThat(types).containsExactly(
                RunEventType.NODE_ENTER,   // a
                RunEventType.NODE_EXIT,    // a
                RunEventType.TRANSITION,   // a -> b
                RunEventType.NODE_ENTER,   // b
                RunEventType.NODE_EXIT,    // b
                RunEventType.GRAPH_COMPLETE);

        // sequence numbers are monotonic from 0
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).sequence()).isEqualTo(i);
        }
    }

    @Test
    void transitionEventCarriesFromToDetail() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .runLog(new InMemoryRunLogStore())
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-2"));

        AgentRunEvent transition = graph.runLog("run-2").stream()
                .filter(e -> e.type() == RunEventType.TRANSITION)
                .findFirst().orElseThrow();
        assertThat(transition.detail()).isEqualTo("a→b");
        assertThat(transition.node()).isEqualTo("a");
    }

    @Test
    void recordsBudgetExceeded() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .budgetPolicy(BudgetPolicy.hierarchical(
                        BudgetLimits.builder().perCall(1.0).build(),
                        (node, ctx) -> 5.0, CostMeter.perCall()))
                .runLog(new InMemoryRunLogStore())
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-3"));

        List<RunEventType> types = graph.runLog("run-3").stream()
                .map(AgentRunEvent::type).toList();
        assertThat(types).contains(RunEventType.BUDGET_EXCEEDED, RunEventType.GRAPH_COMPLETE);
    }

    @Test
    void recordsStateDenied() {
        StateKey<Boolean> CONFIRMED = StateKey.of("payment.confirmed", Boolean.class);
        Agent sneaky = ctx -> AgentResult.builder()
                .text("x").stateUpdates(Map.of(CONFIRMED, true)).completed(true).build();

        AgentGraph graph = AgentGraph.builder()
                .addNode("sneaky", sneaky)
                .statePolicy(StatePolicy.denyWriteKeys("payment.confirmed"))
                .runLog(new InMemoryRunLogStore())
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-4"));

        List<RunEventType> types = graph.runLog("run-4").stream()
                .map(AgentRunEvent::type).toList();
        assertThat(types).contains(RunEventType.STATE_DENIED);
    }

    @Test
    void recordsApprovalRequired() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", ctx -> AgentResult.ofText("done"))
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(new InMemoryCheckpointStore())
                .runLog(new InMemoryRunLogStore())
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-5"));

        AgentRunEvent approval = graph.runLog("run-5").stream()
                .filter(e -> e.type() == RunEventType.APPROVAL_REQUIRED)
                .findFirst().orElseThrow();
        assertThat(approval.node()).isEqualTo("payment.transfer");
        assertThat(approval.detail()).contains("approval");
    }

    @Test
    void noStoreMeansEmptyLogAndZeroOverhead() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-6"));

        assertThat(result.completed()).isTrue();
        assertThat(graph.runLog("run-6")).isEmpty();
    }

    @Test
    void describeIsHumanReadable() {
        AgentRunEvent e = new AgentRunEvent("r", 3, System.currentTimeMillis(),
                RunEventType.NODE_EXIT, "writer", null, 6_000_000L);
        assertThat(e.describe()).contains("#3").contains("NODE_EXIT")
                .contains("writer").contains("6ms");
    }

    @Test
    void runIdsListsKnownRuns() {
        InMemoryRunLogStore store = new InMemoryRunLogStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .runLog(store)
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("alpha"));
        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("beta"));

        assertThat(store.runIds()).containsExactlyInAnyOrder("alpha", "beta");
    }
}
