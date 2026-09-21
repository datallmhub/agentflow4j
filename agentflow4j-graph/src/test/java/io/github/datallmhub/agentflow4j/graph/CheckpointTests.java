package io.github.datallmhub.agentflow4j.graph;

import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CheckpointTests {

    @Test
    void invokeWithRunIdDeletesCheckpointOnNormalCompletion() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("A"))
                .addNode("b", ctx -> AgentResult.ofText("B"))
                .addEdge("a", "b")
                .checkpointStore(store)
                .build();

        AgentResult result = graph.invoke(AgentContext.of("hi"), RunOptions.ofRunId("run-1"));

        assertThat(result.text()).isEqualTo("B");
        assertThat(store.size()).isZero();
    }

    @Test
    void interruptedNodeHaltsAndPersistsCheckpoint() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AtomicInteger askCount = new AtomicInteger();

        Agent asksForInput = ctx -> {
            int n = askCount.incrementAndGet();
            if (n == 1) {
                return AgentResult.interrupted(InterruptRequest.of("Need user confirmation"));
            }
            return AgentResult.ofText("confirmed: " + ctx.messages().get(ctx.messages().size() - 1).getText());
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("ask", asksForInput)
                .addNode("finalize", ctx -> AgentResult.ofText("done"))
                .addEdge("ask", "finalize")
                .checkpointStore(store)
                .build();

        AgentResult first = graph.invoke(AgentContext.of("start"), RunOptions.ofRunId("run-42"));
        assertThat(first.isInterrupted()).isTrue();
        assertThat(first.interrupt().reason()).isEqualTo("Need user confirmation");
        assertThat(store.load("run-42")).isPresent();
        assertThat(store.load("run-42").get().nextNode()).isEqualTo("ask");

        AgentResult resumed = graph.resume("run-42", ResumeOptions.ofMessages(new UserMessage("yes please")));
        assertThat(resumed.text()).isEqualTo("done");
        assertThat(store.size()).isZero();
    }

    @Test
    void resumeWithoutCheckpointThrows() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("A"))
                .checkpointStore(store)
                .build();

        assertThatThrownBy(() -> graph.resume("missing"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invokeWithRunIdRunsWithoutCheckpointStore() {
        // A runId is a general run identifier (used by checkpointing AND the
        // run log). Without a CheckpointStore it simply runs uncheckpointed.
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> AgentResult.ofText("A"))
                .build();

        AgentResult result = graph.invoke(AgentContext.empty(), RunOptions.ofRunId("r"));
        assertThat(result.completed()).isTrue();

        // resume still requires a store — there is nothing to load otherwise
        assertThatThrownBy(() -> graph.resume("r"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inMemoryStoreSaveLoadDelete() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        Checkpoint cp = new Checkpoint("x", "node", AgentContext.empty(), 0, null);
        store.save(cp);
        assertThat(store.load("x")).contains(cp);
        store.delete("x");
        assertThat(store.load("x")).isEmpty();
    }

    @Test
    void checkpointRejectsNegativeIterations() {
        assertThatThrownBy(() ->
                new Checkpoint("r", "n", AgentContext.empty(), -1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void interruptRequestRejectsBlankReason() {
        assertThatThrownBy(() -> InterruptRequest.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InterruptRequest.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentResultInterruptedFactoryBuildsProperResult() {
        AgentResult r = AgentResult.interrupted("need confirmation");
        assertThat(r.isInterrupted()).isTrue();
        assertThat(r.completed()).isFalse();
        assertThat(r.interrupt().reason()).isEqualTo("need confirmation");
    }
}
