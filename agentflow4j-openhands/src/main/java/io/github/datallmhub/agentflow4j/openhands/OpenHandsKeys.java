package io.github.datallmhub.agentflow4j.openhands;

import io.github.datallmhub.agentflow4j.core.StateKey;

/** What an {@link OpenHandsAgent} node writes to the graph's typed state. */
public final class OpenHandsKeys {

    /**
     * The conversation the node started. It is the idempotency key: as long as
     * it is in the state, the node polls that conversation instead of starting
     * a second one, whatever the run does in between.
     */
    public static final StateKey<String> CONVERSATION_ID =
            StateKey.of("openhands.conversation", String.class);

    /** The sandbox running the conversation, needed to pause it. */
    public static final StateKey<String> SANDBOX_ID =
            StateKey.of("openhands.sandbox", String.class);

    /** The branch the agent pushed to, when it pushed one. */
    public static final StateKey<String> BRANCH =
            StateKey.of("openhands.branch", String.class);

    /** The pull request the agent opened, when it opened one. */
    public static final StateKey<Integer> PULL_REQUEST =
            StateKey.of("openhands.pull_request", Integer.class);

    /** What the conversation has cost so far, as OpenHands reports it. */
    public static final StateKey<Double> COST =
            StateKey.of("openhands.cost", Double.class);

    private OpenHandsKeys() {}
}
