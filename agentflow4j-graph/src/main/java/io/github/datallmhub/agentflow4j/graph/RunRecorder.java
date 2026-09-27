package io.github.datallmhub.agentflow4j.graph;

import java.util.concurrent.atomic.AtomicLong;

import org.jspecify.annotations.Nullable;

/**
 * Per-run helper that stamps {@link AgentRunEvent}s with a monotonic sequence
 * and appends them to a {@link RunLogStore}. One instance is created per graph
 * execution. When no store is configured it is a no-op, so the hot path pays
 * nothing.
 */
final class RunRecorder {

    private final String runId;
    @Nullable private final RunLogStore store;
    private final AtomicLong seq = new AtomicLong();

    private RunRecorder(String runId, @Nullable RunLogStore store) {
        this.runId = runId;
        this.store = store;
    }

    static RunRecorder forRun(String runId, @Nullable RunLogStore store) {
        return new RunRecorder(runId, store);
    }

    boolean enabled() {
        return store != null;
    }

    String runId() {
        return runId;
    }

    void record(RunEventType type, @Nullable String node, @Nullable String detail, long durationNanos) {
        if (store == null) {
            return;
        }
        store.append(new AgentRunEvent(runId, seq.getAndIncrement(),
                System.currentTimeMillis(), type, node, detail, durationNanos));
    }

    /** A node the memo shows as already completed, so it did not run again. */
    void skipped(String node) {
        record(RunEventType.NODE_SKIPPED, node, "already completed", 0L);
    }

    void enter(String node) {
        record(RunEventType.NODE_ENTER, node, null, 0L);
    }

    void exit(String node, long durationNanos) {
        record(RunEventType.NODE_EXIT, node, null, durationNanos);
    }

    void error(String node, @Nullable String message) {
        record(RunEventType.NODE_ERROR, node, message, 0L);
    }

    void transition(String from, String to) {
        record(RunEventType.TRANSITION, from, from + "→" + to, 0L);
    }

    void approvalRequired(String node, String reason) {
        record(RunEventType.APPROVAL_REQUIRED, node, reason, 0L);
    }

    void budgetExceeded(String node, String detail) {
        record(RunEventType.BUDGET_EXCEEDED, node, detail, 0L);
    }

    void stateDenied(String node, String detail) {
        record(RunEventType.STATE_DENIED, node, detail, 0L);
    }

    void complete(@Nullable String detail) {
        record(RunEventType.GRAPH_COMPLETE, null, detail, 0L);
    }
}
