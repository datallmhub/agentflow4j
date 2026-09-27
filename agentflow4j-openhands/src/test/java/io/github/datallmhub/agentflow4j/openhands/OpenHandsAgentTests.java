package io.github.datallmhub.agentflow4j.openhands;

import java.time.Duration;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.InMemoryCheckpointStore;
import io.github.datallmhub.agentflow4j.graph.ResumeOptions;
import io.github.datallmhub.agentflow4j.graph.RunOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenHandsAgentTests {

    private OpenHandsAgent.Builder agent(FakeOpenHands server) {
        return OpenHandsAgent.builder()
                .client(OpenHandsClient.builder()
                        .baseUrl(server.baseUrl())
                        .apiKey("test-key")
                        .requestTimeout(Duration.ofSeconds(5))
                        .build())
                .repository("acme/billing")
                .pollInterval(Duration.ofMillis(10));
    }

    @Test
    void asyncModeStartsTheConversationAndInterruptsTheRun() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart().scriptStatuses("running")) {
            AgentResult result = agent(server).build().execute(AgentContext.of("fix the failing test"));

            assertThat(result.isInterrupted()).isTrue();
            assertThat(result.interrupt().reason()).isEqualTo("openhands.running:conv-1");
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.CONVERSATION_ID, "conv-1");
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.SANDBOX_ID, "sb-1");
        }
    }

    @Test
    void anExistingConversationIsPolledInsteadOfStartingASecondOne() {
        try (FakeOpenHands server = new FakeOpenHands().scriptStatuses("finished").pullRequest(42)) {
            AgentContext resumed = AgentContext.of("fix the failing test")
                    .with(OpenHandsKeys.CONVERSATION_ID, "conv-1");

            AgentResult result = agent(server).build().execute(resumed);

            assertThat(server.callsTo("/api/v1/app-conversations")).isEqualTo(1);
            assertThat(server.requests()).noneMatch(r -> r.startsWith("POST /api/v1/app-conversations"));
            assertThat(result.completed()).isTrue();
            assertThat(result.text()).isEqualTo("opened a pull request");
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.PULL_REQUEST, 42);
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.BRANCH, "openhands/fix-42");
            assertThat(result.usage().promptTokens()).isEqualTo(120);
        }
    }

    @Test
    void syncModePollsUntilTheTaskIsDone() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart()
                .scriptStatuses("running", "running", "finished")) {
            AgentResult result = agent(server)
                    .mode(OpenHandsAgent.Mode.SYNC)
                    .build()
                    .execute(AgentContext.of("fix the failing test"));

            assertThat(result.completed()).isTrue();
            assertThat(server.callsTo("/api/v1/app-conversations")).isEqualTo(4); // 1 start + 3 polls
        }
    }

    @Test
    void syncModeGivesUpAfterMaxWait() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart()
                .scriptStatuses("running", "running", "running", "running", "running")) {
            AgentResult result = agent(server)
                    .mode(OpenHandsAgent.Mode.SYNC)
                    .maxWait(Duration.ofMillis(30))
                    .build()
                    .execute(AgentContext.of("fix the failing test"));

            assertThat(result.hasError()).isTrue();
            assertThat(result.error().cause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
            // The conversation id survives the failure, so a rerun does not start a second one.
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.CONVERSATION_ID, "conv-1");
        }
    }

    @Test
    void aStuckAgentAsksForAHuman() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart().scriptStatuses("stuck")) {
            AgentResult result = agent(server).build().execute(AgentContext.of("fix it"));

            assertThat(result.interrupt().reason()).isEqualTo("openhands.stuck:conv-1");
            assertThat(result.interrupt().payload()).isInstanceOf(OpenHandsConversation.class);
        }
    }

    @Test
    void aConfirmationRequestAsksForAHuman() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart().scriptStatuses("waiting_for_confirmation")) {
            AgentResult result = agent(server).build().execute(AgentContext.of("fix it"));

            assertThat(result.interrupt().reason()).isEqualTo("openhands.confirmation:conv-1");
        }
    }

    @Test
    void aFailedAgentIsANodeFailure() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart().scriptStatuses("error")) {
            AgentResult result = agent(server).build().execute(AgentContext.of("fix it"));

            assertThat(result.hasError()).isTrue();
            assertThat(result.error().nodeName()).isEqualTo("openhands");
        }
    }

    @Test
    void aLostSandboxIsANodeFailure() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart()
                .sandboxStatus("MISSING").scriptStatuses("running")) {
            AgentResult result = agent(server).build().execute(AgentContext.of("fix it"));

            assertThat(result.hasError()).isTrue();
            assertThat(result.error().cause()).hasMessageContaining("sandbox");
        }
    }

    @Test
    void aCostAboveTheCapPausesTheSandboxAndInterruptsTheRun() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart().scriptStatuses("running").cost(7.5)) {
            AgentResult result = agent(server).maxCost(5.00).build().execute(AgentContext.of("fix it"));

            assertThat(result.isInterrupted()).isTrue();
            assertThat(result.interrupt().reason()).isEqualTo("budget.exceeded:openhands");
            assertThat(result.stateUpdates()).containsEntry(OpenHandsKeys.COST, 7.5);
            assertThat(server.requests()).anyMatch(r -> r.equals("POST /api/v1/sandboxes/sb-1/pause"));
        }
    }

    @Test
    void aGraphResumesTheSameConversationAfterTheAsyncInterrupt() {
        try (FakeOpenHands server = new FakeOpenHands().readyOnStart()
                .scriptStatuses("running", "finished").pullRequest(7)) {
            InMemoryCheckpointStore store = new InMemoryCheckpointStore();
            AgentGraph graph = AgentGraph.builder()
                    .name("coding-workflow")
                    .addNode("code", agent(server).build())
                    .addNode("announce", ctx -> AgentResult.ofText(
                            "PR #" + ctx.get(OpenHandsKeys.PULL_REQUEST) + " is ready"))
                    .addEdge("code", "announce")
                    .checkpointStore(store)
                    .build();

            AgentResult paused = graph.invoke(AgentContext.of("fix the failing test"),
                    RunOptions.ofRunId("run-oh"));
            assertThat(paused.isInterrupted()).isTrue();
            assertThat(store.load("run-oh")).isPresent();

            AgentResult done = graph.resume("run-oh", ResumeOptions.none());

            assertThat(done.text()).isEqualTo("PR #7 is ready");
            assertThat(server.requests()).filteredOn(r -> r.startsWith("POST /api/v1/app-conversations")).hasSize(1);
        }
    }
}
