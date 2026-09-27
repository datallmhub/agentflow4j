package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record CheckpointDto(
        int version,
        String runId,
        String nextNode,
        List<String> nextNodes,
        int iterations,
        String interruptReason,
        List<String> completedNodes,
        List<MessageDto> messages,
        List<StateEntryDto> state) {

    /**
     * v3 adds the completed-node memo; v2 added the frontier in
     * {@code nextNodes}; v1 only had {@code nextNode}.
     */
    static final int CURRENT_VERSION = 3;

    /** The memo of nodes that already ran, empty on a v1 or v2 payload. */
    java.util.Set<String> completed() {
        return completedNodes == null ? java.util.Set.of() : java.util.Set.copyOf(completedNodes);
    }

    /** The frontier to resume from, tolerating a v1 payload. */
    List<String> frontier() {
        return nextNodes != null && !nextNodes.isEmpty() ? nextNodes : List.of(nextNode);
    }
}
