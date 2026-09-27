package io.github.datallmhub.agentflow4j.openhands;

import java.time.Duration;

import io.github.datallmhub.agentflow4j.graph.FailureCategory;
import io.github.datallmhub.agentflow4j.graph.FailureClassifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenHandsClientTests {

    private OpenHandsClient client(FakeOpenHands server) {
        return OpenHandsClient.builder()
                .baseUrl(server.baseUrl())
                .apiKey("test-key")
                .requestTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void startingAConversationCarriesTheTaskAndTheRepository() {
        try (FakeOpenHands server = new FakeOpenHands()) {
            OpenHandsStartTask task = client(server).startConversation("fix the failing test", "acme/billing");

            assertThat(task.id()).isEqualTo("task-1");
            assertThat(task.status()).isEqualTo(OpenHandsStatus.STARTING);
            assertThat(task.isReady()).isFalse();
            assertThat(server.requests()).contains("POST /api/v1/app-conversations");
        }
    }

    @Test
    void aStartTaskBecomesReadyWithAConversationId() {
        try (FakeOpenHands server = new FakeOpenHands()) {
            OpenHandsStartTask ready = client(server).startTask("task-1");

            assertThat(ready.isReady()).isTrue();
            assertThat(ready.conversationId()).isEqualTo("conv-1");
            assertThat(ready.sandboxId()).isEqualTo("sb-1");
            assertThat(ready.status()).isEqualTo(OpenHandsStatus.IDLE);
        }
    }

    @Test
    void aConversationCarriesStatusMetricsAndResult() {
        try (FakeOpenHands server = new FakeOpenHands().scriptStatuses("finished").cost(1.25).pullRequest(42)) {
            OpenHandsConversation conversation = client(server).conversation("conv-1");

            assertThat(conversation.status()).isEqualTo(OpenHandsStatus.FINISHED);
            assertThat(conversation.status().isTerminal()).isTrue();
            assertThat(conversation.sandboxStatus()).isEqualTo(OpenHandsStatus.RUNNING);
            assertThat(conversation.summary()).isEqualTo("opened a pull request");
            assertThat(conversation.repository()).isEqualTo("acme/billing");
            assertThat(conversation.branch()).isEqualTo("openhands/fix-42");
            assertThat(conversation.pullRequest()).isEqualTo(42);
            assertThat(conversation.accumulatedCost()).isEqualTo(1.25);
            assertThat(conversation.promptTokens()).isEqualTo(120);
            assertThat(conversation.completionTokens()).isEqualTo(45);
        }
    }

    @Test
    void aLostSandboxIsVisibleOnTheConversation() {
        try (FakeOpenHands server = new FakeOpenHands().sandboxStatus("MISSING").scriptStatuses("error")) {
            OpenHandsConversation conversation = client(server).conversation("conv-1");

            assertThat(conversation.sandboxLost()).isTrue();
            assertThat(conversation.status()).isEqualTo(OpenHandsStatus.ERROR);
        }
    }

    @Test
    void anErrorResponseCarriesTheStatusAndTheBody() {
        try (FakeOpenHands server = new FakeOpenHands().failEveryCallWith(404, "{\"error\":\"no such conversation\"}")) {
            assertThatThrownBy(() -> client(server).conversation("conv-1"))
                    .isInstanceOfSatisfying(OpenHandsException.class, ex -> {
                        assertThat(ex.statusCode()).isEqualTo(404);
                        assertThat(ex.getMessage()).contains("no such conversation");
                    });
        }
    }

    @Test
    void unreachableServerFailsWithoutAStatus() {
        OpenHandsClient client = OpenHandsClient.builder()
                .baseUrl(java.net.URI.create("http://127.0.0.1:1"))
                .apiKey("test-key")
                .connectTimeout(Duration.ofMillis(300))
                .requestTimeout(Duration.ofMillis(500))
                .build();

        assertThatThrownBy(() -> client.conversation("conv-1"))
                .isInstanceOfSatisfying(OpenHandsException.class, ex -> assertThat(ex.statusCode()).isZero());
    }

    @Test
    void theClassifierRetriesRateLimitsAndServerErrorsOnly() {
        FailureClassifier classifier = OpenHandsFailureClassifier.INSTANCE;

        assertThat(classifier.classify(new OpenHandsException("m", 429, null)).category())
                .isEqualTo(FailureCategory.TRANSIENT);
        assertThat(classifier.classify(new OpenHandsException("m", 429, null)).retryAfter())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(classifier.classify(new OpenHandsException("m", 503, null)).category())
                .isEqualTo(FailureCategory.TRANSIENT);
        assertThat(classifier.classify(new OpenHandsException("m", 400, null)).category())
                .isEqualTo(FailureCategory.PERMANENT);
        assertThat(classifier.classify(new OpenHandsException("m", 402, null)).category())
                .isEqualTo(FailureCategory.OVER_BUDGET);
        assertThat(classifier.classify(new OpenHandsException("m", new java.io.IOException("reset"))).category())
                .isEqualTo(FailureCategory.TRANSIENT);
        assertThat(classifier.classify(new IllegalStateException("unrelated"))).isNull();
    }

    @Test
    void unknownStatusesDoNotBreakTheMapping() {
        assertThat(OpenHandsStatus.of("SOMETHING_NEW")).isEqualTo(OpenHandsStatus.UNKNOWN);
        assertThat(OpenHandsStatus.of(null)).isEqualTo(OpenHandsStatus.UNKNOWN);
        assertThat(OpenHandsStatus.of("waiting_for_confirmation"))
                .isEqualTo(OpenHandsStatus.WAITING_FOR_CONFIRMATION);
        assertThat(OpenHandsStatus.UNKNOWN.isTerminal()).isFalse();
    }
}
