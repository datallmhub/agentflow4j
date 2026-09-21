package io.github.datallmhub.agentflow4j.graph;

import java.util.Objects;

/**
 * Structured payload attached to the {@code InterruptRequest} emitted when
 * an {@link ApprovalGate} requires human approval before a node runs.
 *
 * <p>UI / Slack / email integrations should display {@link #reason} and
 * {@link #nodeName} to the operator, then call
 * {@link AgentGraph#resume(String, ResumeOptions)} to continue.
 */
public record ApprovalRequest(String nodeName, String reason) {

    public ApprovalRequest {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(reason, "reason");
    }
}
