package io.github.datallmhub.agentflow4j.squad;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;
import org.jspecify.annotations.Nullable;

final class RecordingToolCallback implements ToolCallback {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolCallback delegate;
    private final ToolCallCollector collector;

    RecordingToolCallback(ToolCallback delegate, ToolCallCollector collector) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return record(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return record(toolInput, toolContext);
    }

    private String record(String toolInput, @Nullable ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        String raw = toolInput == null ? "" : toolInput;
        Map<String, Object> arguments = parseArgs(raw);
        int sequence = collector.nextSequence();
        collector.onStart(name, arguments);
        long start = System.nanoTime();
        try {
            String result = toolContext == null
                    ? delegate.call(raw)
                    : delegate.call(raw, toolContext);
            long durationMs = elapsedMs(start);
            collector.onEnd(ToolCallRecord.success(sequence, name, arguments, result, durationMs));
            return result;
        } catch (RuntimeException ex) {
            long durationMs = elapsedMs(start);
            collector.onEnd(ToolCallRecord.failure(sequence, name, arguments, describe(unwrap(ex)), durationMs));
            throw ex;
        }
    }

    private static Map<String, Object> parseArgs(String input) {
        if (input.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonParser.getObjectMapper().readValue(input, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of("_raw", input);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Spring AI's ToolExecutionException is a carrier; the audit wants the underlying failure. */
    private static Throwable unwrap(RuntimeException ex) {
        return ex instanceof ToolExecutionException && ex.getCause() != null ? ex.getCause() : ex;
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage();
        return msg == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + msg;
    }
}
