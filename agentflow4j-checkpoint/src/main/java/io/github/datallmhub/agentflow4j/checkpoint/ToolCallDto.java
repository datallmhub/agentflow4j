package io.github.datallmhub.agentflow4j.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ToolCallDto(String id, String name, String arguments) {}
