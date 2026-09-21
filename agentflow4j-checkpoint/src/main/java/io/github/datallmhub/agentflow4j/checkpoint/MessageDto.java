package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record MessageDto(
        int version,
        String role,
        String text,
        List<ToolCallDto> toolCalls,
        Map<String, Object> metadata) {

    static final int CURRENT_VERSION = 1;
}
