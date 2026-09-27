package io.github.datallmhub.agentflow4j.graph;

import java.util.List;
import java.util.Objects;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import org.jspecify.annotations.Nullable;

/**
 * The state a run resumes from. {@code nextNodes} is the frontier the graph
 * continues with: a single node for a sequential run, several when independent
 * branches are in flight.
 */
public record Checkpoint(
        String runId,
        List<String> nextNodes,
        AgentContext context,
        int iterations,
        @Nullable InterruptRequest interrupt) {

    public Checkpoint {
        Objects.requireNonNull(runId, "runId");
        nextNodes = List.copyOf(Objects.requireNonNull(nextNodes, "nextNodes"));
        if (nextNodes.isEmpty()) {
            throw new IllegalArgumentException("nextNodes must not be empty");
        }
        Objects.requireNonNull(context, "context");
        if (iterations < 0) {
            throw new IllegalArgumentException("iterations must be >= 0");
        }
    }

    public Checkpoint(String runId, String nextNode, AgentContext context, int iterations,
                      @Nullable InterruptRequest interrupt) {
        this(runId, List.of(Objects.requireNonNull(nextNode, "nextNode")), context, iterations, interrupt);
    }

    /** The first node of the frontier; the whole frontier is {@link #nextNodes()}. */
    public String nextNode() {
        return nextNodes.get(0);
    }

    public boolean isInterrupted() {
        return interrupt != null;
    }
}
