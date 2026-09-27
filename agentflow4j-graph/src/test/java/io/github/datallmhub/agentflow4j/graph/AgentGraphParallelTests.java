package io.github.datallmhub.agentflow4j.graph;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGraphParallelTests {

    private static final StateKey<String> LEFT = StateKey.of("left", String.class);
    private static final StateKey<String> RIGHT = StateKey.of("right", String.class);
    private static final StateKey<String> SHARED = StateKey.of("shared", String.class);

    @Test
    void branchesOfAFanOutRunAtTheSameTime() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        Agent branch = ctx -> {
            bothEntered.countDown();
            try {
                // Fails unless the other branch is running concurrently.
                assertThat(bothEntered.await(2, TimeUnit.SECONDS)).isTrue();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentResult.ofText("done");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("a", branch)
                .addNode("b", branch)
                .addEdge("fork", "a")
                .addEdge("fork", "b")
                .build();

        graph.invoke(AgentContext.of("go"));

        assertThat(bothEntered.getCount()).isZero();
    }

    @Test
    void stateOfBothBranchesIsMergedAndTheJoinRunsOnce() {
        AtomicInteger joinCalls = new AtomicInteger();
        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("a", ctx -> AgentResult.builder().stateUpdates(Map.of(LEFT, "L")).completed(true).build())
                .addNode("b", ctx -> AgentResult.builder().stateUpdates(Map.of(RIGHT, "R")).completed(true).build())
                .addNode("join", ctx -> {
                    joinCalls.incrementAndGet();
                    return AgentResult.ofText(ctx.get(LEFT) + "+" + ctx.get(RIGHT));
                })
                .addEdge("fork", "a")
                .addEdge("fork", "b")
                .addEdge("a", "join")
                .addEdge("b", "join")
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.text()).isEqualTo("L+R");
        assertThat(joinCalls.get()).isEqualTo(1);
    }

    @Test
    void conflictingWritesFailTheRun() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("a", ctx -> AgentResult.builder().stateUpdates(Map.of(SHARED, "from-a")).completed(true).build())
                .addNode("b", ctx -> AgentResult.builder().stateUpdates(Map.of(SHARED, "from-b")).completed(true).build())
                .addEdge("fork", "a")
                .addEdge("fork", "b")
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"));

        assertThat(result.hasError()).isTrue();
        assertThat(result.error().cause()).isInstanceOfSatisfying(StateConflictException.class, conflict -> {
            assertThat(conflict.key()).isEqualTo(SHARED);
            assertThat(List.of(conflict.firstNode(), conflict.secondNode())).containsExactlyInAnyOrder("a", "b");
        });
    }

    @Test
    void identicalWritesFromTwoBranchesAreNotAConflict() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("a", ctx -> AgentResult.builder().stateUpdates(Map.of(SHARED, "same")).completed(true).build())
                .addNode("b", ctx -> AgentResult.builder().stateUpdates(Map.of(SHARED, "same")).completed(true).build())
                .addNode("join", ctx -> AgentResult.ofText(ctx.get(SHARED)))
                .addEdge("fork", "a")
                .addEdge("fork", "b")
                .addEdge("a", "join")
                .addEdge("b", "join")
                .build();

        assertThat(graph.invoke(AgentContext.of("go")).text()).isEqualTo("same");
    }

    @Test
    void approvalPausesOnlyTheGatedBranch() {
        AtomicInteger gatedCalls = new AtomicInteger();
        List<String> ran = new CopyOnWriteArrayList<>();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();

        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("report", ctx -> {
                    ran.add("report");
                    return AgentResult.builder().stateUpdates(Map.of(LEFT, "report")).completed(true).build();
                })
                .addNode("payment.transfer", ctx -> {
                    gatedCalls.incrementAndGet();
                    ran.add("payment.transfer");
                    return AgentResult.ofText("transferred");
                })
                .addEdge("fork", "report")
                .addEdge("fork", "payment.transfer")
                .approvalGate(ApprovalGate.requireFor("payment.transfer"))
                .checkpointStore(store)
                .build();

        AgentResult paused = graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-p"));

        assertThat(paused.isInterrupted()).isTrue();
        assertThat(ran).containsExactly("report");
        assertThat(gatedCalls.get()).isZero();
        assertThat(store.load("run-p")).isPresent();
        assertThat(store.load("run-p").get().nextNodes()).contains("payment.transfer");

        AgentResult resumed = graph.resume("run-p", ResumeOptions.ofApproval("payment.transfer"));

        assertThat(resumed.text()).isEqualTo("transferred");
        assertThat(gatedCalls.get()).isEqualTo(1);
        // The branch that already ran is not replayed.
        assertThat(ran).containsExactly("report", "payment.transfer");
    }

    @Test
    void aJoinWaitsForABranchHeldBackByApproval() {
        AtomicInteger joinCalls = new AtomicInteger();
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .addNode("free", ctx -> AgentResult.builder().stateUpdates(Map.of(LEFT, "free")).completed(true).build())
                .addNode("paid", ctx -> AgentResult.builder().stateUpdates(Map.of(RIGHT, "paid")).completed(true).build())
                .addNode("join", ctx -> {
                    joinCalls.incrementAndGet();
                    return AgentResult.ofText(ctx.get(LEFT) + "+" + ctx.get(RIGHT));
                })
                .addEdge("fork", "free")
                .addEdge("fork", "paid")
                .addEdge("free", "join")
                .addEdge("paid", "join")
                .approvalGate(ApprovalGate.requireFor("paid"))
                .checkpointStore(store)
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("run-j"));
        assertThat(joinCalls.get()).as("the join must not run before the gated branch").isZero();

        AgentResult result = graph.resume("run-j", ResumeOptions.ofApproval("paid"));

        assertThat(joinCalls.get()).isEqualTo(1);
        assertThat(result.text()).isEqualTo("free+paid");
    }

    @Test
    void maxConcurrencyBoundsTheNumberOfBranchesInFlight() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Agent branch = ctx -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            inFlight.decrementAndGet();
            return AgentResult.ofText("ok");
        };

        AgentGraph.Builder builder = AgentGraph.builder()
                .addNode("fork", ctx -> AgentResult.ofText("fork"))
                .maxConcurrency(2);
        for (int i = 0; i < 6; i++) {
            builder.addNode("b" + i, branch).addEdge("fork", "b" + i);
        }

        builder.build().invoke(AgentContext.of("go"));

        assertThat(peak.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void singleDirectEdgeStillRunsSequentially() {
        List<String> order = new CopyOnWriteArrayList<>();
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", ctx -> { order.add("a"); return AgentResult.ofText("a"); })
                .addNode("b", ctx -> { order.add("b"); return AgentResult.ofText("b"); })
                .addEdge("a", "b")
                .build();

        assertThat(graph.invoke(AgentContext.empty()).text()).isEqualTo("b");
        assertThat(order).containsExactly("a", "b");
    }
}
