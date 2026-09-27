package io.github.datallmhub.agentflow4j.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
public record ResumeOptions(Set<String> approvedNodes, List<Message> messages,
                            Map<String, String> rejectedNodes, Set<String> invalidatedNodes) {

    private static final ResumeOptions NONE =
            new ResumeOptions(Set.of(), List.of(), Map.of(), Set.of());

    public ResumeOptions {
        approvedNodes = Set.copyOf(Objects.requireNonNull(approvedNodes, "approvedNodes"));
        messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        rejectedNodes = Map.copyOf(rejectedNodes == null ? Map.of() : rejectedNodes);
        invalidatedNodes = Set.copyOf(invalidatedNodes == null ? Set.of() : invalidatedNodes);
    }

    public ResumeOptions(Set<String> approvedNodes, List<Message> messages) {
        this(approvedNodes, messages, Map.of(), Set.of());
    }

    /** Rejects a gated node: the run continues at the target declared by
     * {@code AgentGraph.Builder.onRejection(node, target)}, or ends. */
    public static ResumeOptions ofRejection(String node, String reason) {
        return NONE.withRejection(node, reason);
    }

    public ResumeOptions withRejection(String node, String reason) {
        Map<String, String> rejections = new LinkedHashMap<>(rejectedNodes);
        rejections.put(Objects.requireNonNull(node, "node"), Objects.requireNonNull(reason, "reason"));
        return new ResumeOptions(approvedNodes, messages, rejections, invalidatedNodes);
    }

    /** Forces a node that already ran to run again on this resume. */
    public ResumeOptions withInvalidated(String node) {
        Set<String> invalidated = new LinkedHashSet<>(invalidatedNodes);
        invalidated.add(Objects.requireNonNull(node, "node"));
        return new ResumeOptions(approvedNodes, messages, rejectedNodes, invalidated);
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
        return new ResumeOptions(nodes, messages, rejectedNodes, invalidatedNodes);
    }

    public ResumeOptions withMessages(Message... additional) {
        List<Message> all = new ArrayList<>(messages);
        all.addAll(List.of(additional));
        return new ResumeOptions(approvedNodes, all, rejectedNodes, invalidatedNodes);
    }
}
