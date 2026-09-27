package io.github.datallmhub.agentflow4j.openhands;

import org.jspecify.annotations.Nullable;

/**
 * A snapshot of an OpenHands conversation.
 *
 * @param id             the conversation id, the handle for everything else
 * @param sandboxId      the sandbox running the agent
 * @param status         the agent's execution status
 * @param sandboxStatus  the sandbox lifecycle status
 * @param summary        the agent's last message, when the API returned one
 * @param repository     the repository the agent worked on
 * @param branch         the branch it pushed to
 * @param pullRequest    the pull request it opened
 * @param accumulatedCost what the run has cost so far
 * @param promptTokens   prompt tokens billed so far
 * @param completionTokens completion tokens billed so far
 */
public record OpenHandsConversation(
        String id,
        @Nullable String sandboxId,
        OpenHandsStatus status,
        OpenHandsStatus sandboxStatus,
        @Nullable String summary,
        @Nullable String repository,
        @Nullable String branch,
        @Nullable Integer pullRequest,
        double accumulatedCost,
        long promptTokens,
        long completionTokens) {

    /** The sandbox is gone or broken, so the conversation cannot progress. */
    public boolean sandboxLost() {
        return sandboxStatus == OpenHandsStatus.ERROR;
    }
}
