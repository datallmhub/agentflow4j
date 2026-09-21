package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record CheckpointDto(
        int version,
        String runId,
        String nextNode,
        int iterations,
        String interruptReason,
        List<MessageDto> messages,
        List<StateEntryDto> state) {

    static final int CURRENT_VERSION = 1;
}
