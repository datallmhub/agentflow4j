package io.github.datallmhub.agentflow4j.openhands;

import org.jspecify.annotations.Nullable;

/**
 * The handle returned when a conversation is requested. OpenHands prepares the
 * sandbox first, so {@code conversationId} is only populated once the task is
 * ready.
 */
public record OpenHandsStartTask(
        String id,
        OpenHandsStatus status,
        @Nullable String conversationId,
        @Nullable String sandboxId) {

    public boolean isReady() {
        return conversationId != null && !conversationId.isBlank();
    }
}
