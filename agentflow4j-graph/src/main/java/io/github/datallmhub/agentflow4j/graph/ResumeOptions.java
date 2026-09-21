package io.github.datallmhub.agentflow4j.graph;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.ai.chat.messages.Message;

/**
 * Settings for {@link AgentGraph#resume(String, ResumeOptions)}.
 *
 * <pre>{@code
 * graph.resume("ticket-42", ResumeOptions.ofApproval("payment.transfer")
 *         .withMessages(new UserMessage("approved by alice")));
 * }</pre>
 *
 * @param approvedNodes nodes marked as approved so the built-in
 *                      {@link ApprovalGate} factories let them run
 * @param messages      appended to the checkpointed context before the run
 *                      continues
 */
public record ResumeOptions(Set<String> approvedNodes, List<Message> messages) {

    private static final ResumeOptions NONE = new ResumeOptions(Set.of(), List.of());

    public ResumeOptions {
        approvedNodes = Set.copyOf(Objects.requireNonNull(approvedNodes, "approvedNodes"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
    }

    public static ResumeOptions none() {
        return NONE;
    }

    public static ResumeOptions ofApproval(String node) {
        return NONE.withApproval(node);
    }

    public static ResumeOptions ofMessages(Message... messages) {
        return NONE.withMessages(messages);
    }

    public ResumeOptions withApproval(String node) {
        Set<String> nodes = new LinkedHashSet<>(approvedNodes);
        nodes.add(Objects.requireNonNull(node, "node"));
        return new ResumeOptions(nodes, messages);
    }

    public ResumeOptions withMessages(Message... additional) {
        List<Message> all = new ArrayList<>(messages);
        all.addAll(List.of(additional));
        return new ResumeOptions(approvedNodes, all);
    }
}
