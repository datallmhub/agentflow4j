package io.github.datallmhub.agentflow4j.graph;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;

public interface AgentListener {

    default void onNodeEnter(String graphName, String nodeName, AgentContext context) {}

    default void onNodeExit(String graphName, String nodeName, AgentResult result, long durationNanos) {}

    default void onNodeError(String graphName, String nodeName, AgentError error) {}

    /** Called each time the runtime moves from one node to another. */
    default void onTransition(String graphName, String from, String to) {}

    default void onGraphComplete(String graphName, AgentResult result) {}

    /** Called after a checkpoint has been persisted to the {@link CheckpointStore}. */
    default void onCheckpoint(String graphName, Checkpoint checkpoint) {}

    /**
     * Called once per tool call a node reported in its {@link AgentResult},
     * after the node has returned and before {@link #onNodeExit}.
     */
    default void onToolCall(String graphName, String nodeName, ToolCallRecord call) {}

    /** Called when an {@link ApprovalGate} pauses the run before {@code request.nodeName()}. */
    default void onApprovalRequired(String graphName, ApprovalRequest request) {}

    /**
     * Called when a node is interrupted for exceeding its budget. The
     * interrupt payload is a {@link BudgetPolicy.Breach} when the
     * {@link BudgetPolicy} refused the call, or a reason string when a
     * {@link FailureClassifier} classified the failure as over budget.
     */
    default void onBudgetExceeded(String graphName, String nodeName, InterruptRequest interrupt) {}
}
