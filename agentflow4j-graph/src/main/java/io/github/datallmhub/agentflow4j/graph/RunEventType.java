package io.github.datallmhub.agentflow4j.graph;

/**
 * Kinds of events captured in a {@link RunLog}. The lifecycle events mirror
 * the {@link AgentListener} callbacks; the governance events make the gates
 * ({@link BudgetPolicy}, {@link StatePolicy}, {@link ApprovalGate}) visible
 * in the timeline — which is the whole point of an execution log.
 */
public enum RunEventType {
    NODE_ENTER,
    NODE_EXIT,
    NODE_SKIPPED,
    NODE_ERROR,
    TRANSITION,
    APPROVAL_REQUIRED,
    BUDGET_EXCEEDED,
    STATE_DENIED,
    GRAPH_COMPLETE
}
