package io.github.datallmhub.agentflow4j.checkpoint;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
record StateEntryDto(String key, String type, Object value) {}
