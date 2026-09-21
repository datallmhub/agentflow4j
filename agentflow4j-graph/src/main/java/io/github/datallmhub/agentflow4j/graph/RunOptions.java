package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Per-run settings for {@link AgentGraph#invoke(io.github.datallmhub.agentflow4j.core.AgentContext, RunOptions)}
 * and {@link AgentGraph#invokeStream(io.github.datallmhub.agentflow4j.core.AgentContext, RunOptions)}.
 *
 * <pre>{@code
 * graph.invoke(ctx, RunOptions.ofRunId("ticket-42").withTimeout(Duration.ofMinutes(5)));
 * }</pre>
 *
 * @param runId   identifies the run for checkpointing and the run log; when
 *                {@code null} the run gets a random id and is not checkpointed
 * @param timeout wall-clock budget checked before each node is entered;
 *                {@code null} means no timeout
 */
public record RunOptions(@Nullable String runId, @Nullable Duration timeout) {

    private static final RunOptions DEFAULTS = new RunOptions(null, null);

    public RunOptions {
        if (timeout != null && timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
    }

    public static RunOptions defaults() {
        return DEFAULTS;
    }

    public static RunOptions ofRunId(String runId) {
        return DEFAULTS.withRunId(runId);
    }

    public static RunOptions ofTimeout(Duration timeout) {
        return DEFAULTS.withTimeout(timeout);
    }

    public RunOptions withRunId(String runId) {
        return new RunOptions(Objects.requireNonNull(runId, "runId"), timeout);
    }

    public RunOptions withTimeout(Duration timeout) {
        return new RunOptions(runId, Objects.requireNonNull(timeout, "timeout"));
    }
}
