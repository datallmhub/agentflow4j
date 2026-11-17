package io.github.datallmhub.agentflow4j.graph;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One entry in a {@link RunLogStore} — a timestamped, ordered record of something
 * that happened during a graph run. Designed to be trivially serializable
 * (all fields are primitives or strings) so it can be written to JSON, a
 * log pipeline, or rendered in a timeline UI.
 *
 * @param runId         the run this event belongs to
 * @param sequence      monotonic index within the run, starting at 0
 * @param epochMillis   wall-clock time the event was recorded
 * @param type          what happened
 * @param node          the node involved, or {@code null} for run-level events
 * @param detail        short human-readable context (transition target,
 *                      error message, breach reason, ...), or {@code null}
 * @param durationNanos node execution time for {@link RunEventType#NODE_EXIT},
 *                      else 0
 */
public record AgentRunEvent(
        String runId,
        long sequence,
        long epochMillis,
        RunEventType type,
        @Nullable String node,
        @Nullable String detail,
        long durationNanos) {

    public AgentRunEvent {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(type, "type");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
    }

    /** Compact single-line rendering, handy for logs and debugging. */
    public String describe() {
        StringBuilder sb = new StringBuilder()
                .append('#').append(sequence).append(' ').append(type);
        if (node != null) {
            sb.append(" node=").append(node);
        }
        if (type == RunEventType.NODE_EXIT) {
            sb.append(" (").append(durationNanos / 1_000_000L).append("ms)");
        }
        if (detail != null) {
            sb.append(" — ").append(detail);
        }
        return sb.toString();
    }
}
