package io.github.datallmhub.agentflow4j.graph;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphApprovalGateTests {

    @Test
    void gateInterruptsBeforeFlaggedNodeAndPersistsCheckpoint() {
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

        AgentResult result = graph.invoke(AgentContext.of("transfer 500"), RunOptions.ofRunId("run-1"));

        assertThat(guardedCalls.get()).isZero();
        assertThat(result.isInterrupted()).isTrue();
        assertThat(result.interrupt().reason()).isEqualTo("approval.required:payment.transfer");
        assertThat(result.interrupt().payload())
                .isInstanceOfSatisfying(ApprovalRequest.class, req -> {
                    assertThat(req.nodeName()).isEqualTo("payment.transfer");
                    assertThat(req.reason()).contains("payment.transfer");
                });
        assertThat(store.load("run-1")).isPresent();
    }

    @Test
    void resumeWithApprovedNodeBypassesGateAndCompletesNode() {
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

        // First run: paused
        AgentResult paused = graph.invoke(AgentContext.of("transfer 500"), RunOptions.ofRunId("run-2"));
        assertThat(paused.isInterrupted()).isTrue();
        assertThat(guardedCalls.get()).isZero();

        // Human approves and resumes
        AgentResult resumed = graph.resume("run-2", ResumeOptions.ofApproval("payment.transfer"));

        assertThat(resumed.completed()).isTrue();
        assertThat(resumed.text()).isEqualTo("transferred");
        assertThat(guardedCalls.get()).isEqualTo(1);
    }

    @Test
    void unapprovedNodesStayGated() {
        Agent transfer = ctx -> AgentResult.ofText("transferred");
        Agent refund = ctx -> AgentResult.ofText("refunded");

        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("payment.transfer", transfer)
                .addNode("refund.process", refund)
                .addEdge("payment.transfer", "refund.process")
                .approvalGate(ApprovalGate.requireFor("payment.transfer", "refund.process"))
                .checkpointStore(store)
                .build();

        // First call paused on transfer
        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-3"));
        // Approve transfer, run continues, then pauses again on refund
        AgentResult second = graph.resume("run-3", ResumeOptions.ofApproval("payment.transfer"));

        assertThat(second.isInterrupted()).isTrue();
        ApprovalRequest req = (ApprovalRequest) second.interrupt().payload();
        assertThat(req.nodeName()).isEqualTo("refund.process");

        // Approve refund as well, now it completes
        AgentResult third = graph.resume("run-3", ResumeOptions.ofApproval("refund.process"));
        assertThat(third.completed()).isTrue();
        assertThat(third.text()).isEqualTo("refunded");
    }

    @Test
    void resumeWithoutCheckpointStoreFails() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("ok"))
                .approvalGate(ApprovalGate.requireFor("a"))
                .build();

        try {
            graph.resume("missing-run", ResumeOptions.ofApproval("a"));
            assertThat(false).as("expected IllegalStateException").isTrue();
        }
        catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("CheckpointStore");
        }
    }
}
