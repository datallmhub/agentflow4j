package io.github.datallmhub.agentflow4j.graph;

import io.github.datallmhub.agentflow4j.core.StateKey;

/**
 * Thrown when two branches running in parallel write different values to the
 * same {@link StateKey}. The run fails instead of picking a winner, so a graph
 * never depends on which branch happened to finish first.
 */
public final class StateConflictException extends RuntimeException {

    private final StateKey<?> key;
    private final String firstNode;
    private final String secondNode;

    public StateConflictException(StateKey<?> key, String firstNode, String secondNode) {
        super("parallel branches '" + firstNode + "' and '" + secondNode
                + "' wrote different values to state key '" + key.name() + "'");
        this.key = key;
        this.firstNode = firstNode;
        this.secondNode = secondNode;
    }

    public StateKey<?> key() {
        return key;
    }

    public String firstNode() {
        return firstNode;
    }

    public String secondNode() {
        return secondNode;
    }
}
