package io.github.datallmhub.agentflow4j.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphLifecycleHooksTests {

    @Test
    void checkpointHookFiresForEverySave() {
        RecordingListener listener = new RecordingListener();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .checkpointStore(new InMemoryCheckpointStore())
                .listener(listener)
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-1"));

        assertThat(listener.events).containsExactly(
                "checkpoint:a", "exit:a", "checkpoint:b", "exit:b");
    }

    @Test
    void toolCallHookFiresBeforeNodeExit() {
        RecordingListener listener = new RecordingListener();
        AgentResult withTools = AgentResult.builder()
                .text("done")
                .toolCalls(List.of(
                        ToolCallRecord.success(1, "lookup", Map.of(), "found", 3),
                        ToolCallRecord.failure(2, "refund", Map.of(), "denied", 1)))
                .completed(true)
                .build();
        AgentGraph graph = AgentGraph.builder()
                .addNode("agent", ctx -> withTools)
                .listener(listener)
                .build();

        graph.invoke(AgentContext.of("go"));

        assertThat(listener.events).containsExactly(
                "tool:agent:lookup", "tool:agent:refund", "exit:agent");
    }

    @Test
    void approvalHookCarriesTheRequest() {
        RecordingListener listener = new RecordingListener();
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", ctx -> AgentResult.ofText("transferred"))
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .listener(listener)
                .build();

        graph.invoke(AgentContext.of("go"));

        assertThat(listener.events).containsExactly("approval:payment.transfer", "exit:payment.transfer");
    }

    @Test
    void budgetHookCarriesTheBreach() {
        RecordingListener listener = new RecordingListener();
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.builder().perRun(1).build(),
                (node, ctx) -> 1.0,
                CostMeter.perCall());
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .budgetPolicy(budget)
                .listener(listener)
                .build();

        graph.invoke(AgentContext.of("go"));

        assertThat(listener.events).containsExactly("exit:a", "exit:b", "budget:b");
        assertThat(listener.budgetInterrupt.payload()).isInstanceOf(BudgetPolicy.Breach.class);
    }

    @Test
    void streamingFiresTheSameHooks() {
        RecordingListener listener = new RecordingListener();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .addNode("payment.transfer", ctx -> AgentResult.ofText("transferred"))
                .addEdge("a", "payment.transfer")
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(new InMemoryCheckpointStore())
                .listener(listener)
                .build();

        graph.invokeStream(AgentContext.of("go"), RunOptions.ofRunId("run-2")).blockLast();

        assertThat(listener.events).containsExactly(
                "checkpoint:a", "exit:a", "checkpoint:payment.transfer",
                "approval:payment.transfer", "checkpoint:payment.transfer", "exit:payment.transfer");
    }

    @Test
    void failingListenerDoesNotBreakTheRun() {
        AgentListener throwing = new AgentListener() {
            @Override
            public void onCheckpoint(String graphName, Checkpoint checkpoint) {
                throw new IllegalStateException("listener bug");
            }
        };
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("a"))
                .checkpointStore(new InMemoryCheckpointStore())
                .listener(throwing)
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-3"));

        assertThat(result.text()).isEqualTo("a");
    }

    private static final class RecordingListener implements AgentListener {

        final List<String> events = new CopyOnWriteArrayList<>();
        InterruptRequest budgetInterrupt;

        @Override
        public void onNodeExit(String graphName, String nodeName, AgentResult result, long durationNanos) {
            events.add("exit:" + nodeName);
        }

        @Override
        public void onCheckpoint(String graphName, Checkpoint checkpoint) {
            events.add("checkpoint:" + checkpoint.nextNode());
        }

        @Override
        public void onToolCall(String graphName, String nodeName, ToolCallRecord call) {
            events.add("tool:" + nodeName + ":" + call.name());
        }

        @Override
        public void onApprovalRequired(String graphName, ApprovalRequest request) {
            events.add("approval:" + request.nodeName());
        }

        @Override
        public void onBudgetExceeded(String graphName, String nodeName, InterruptRequest interrupt) {
            events.add("budget:" + nodeName);
            budgetInterrupt = interrupt;
        }
    }
}
