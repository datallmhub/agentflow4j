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
        List<MessageDto> messages,
        List<StateEntryDto> state) {

    /** v2 records the whole frontier in {@code nextNodes}; v1 only had {@code nextNode}. */
    static final int CURRENT_VERSION = 2;

    /** The frontier to resume from, tolerating a v1 payload. */
    List<String> frontier() {
        return nextNodes != null && !nextNodes.isEmpty() ? nextNodes : List.of(nextNode);
    }
}
