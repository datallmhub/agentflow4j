package io.github.datallmhub.agentflow4j.squad;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.datallmhub.agentflow4j.graph.ToolPolicy;
import io.github.datallmhub.agentflow4j.graph.ToolPolicyViolation;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.util.json.JsonParser;

/**
 * Wraps a Spring AI {@link ToolCallback} and consults a {@link ToolPolicy}
 * before invoking it. A denied call throws a {@link ToolExecutionException}
 * whose cause is the {@link ToolPolicyViolation}: Spring AI hands that back
 * to the model as the tool result, so the model learns the call was refused
 * and can answer accordingly, while the delegate is never invoked.
 *
 * <p>Stacked with {@link RecordingToolCallback}, the order is
 * {@code Recording(Policy(real))} so denied attempts are captured by the
 * audit trail as failed tool calls.
 */
final class PolicyToolCallback implements ToolCallback {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolCallback delegate;
    private final ToolPolicy policy;

    PolicyToolCallback(ToolCallback delegate, ToolPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
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
        return enforce(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return enforce(toolInput, toolContext);
    }

    private String enforce(String toolInput, @Nullable ToolContext toolContext) {
        String name = delegate.getToolDefinition().name();
        String raw = toolInput == null ? "" : toolInput;
        Map<String, Object> arguments = parseArgs(raw);
        ToolPolicy.Decision decision = policy.check(name, arguments);
        if (decision.denied()) {
            throw new PolicyDenial(delegate.getToolDefinition(), new ToolPolicyViolation(name, arguments,
                    decision.reason() != null ? decision.reason() : "denied"));
        }
        return toolContext == null
                ? delegate.call(raw)
                : delegate.call(raw, toolContext);
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

    /** Surfaces the violation's own message, which is what the model reads. */
    static final class PolicyDenial extends ToolExecutionException {

        PolicyDenial(ToolDefinition tool, ToolPolicyViolation violation) {
            super(tool, violation);
        }

        @Override
        public String getMessage() {
            return getCause().getMessage();
        }
    }
}
