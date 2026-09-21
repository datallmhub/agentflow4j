package io.github.datallmhub.agentflow4j.graph;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentGraphTests {

    private static final StateKey<String> TRACE = StateKey.of("trace", String.class);

    @Test
    void invokeWithTimeoutFailsBeforeEnteringNextNodeOnceDeadlineExceeded() {
        Agent slow = ctx -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            return AgentResult.ofText("slow");
        };
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", slow)
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .build();

        AgentResult result = graph.invoke(AgentContext.of("go"), java.time.Duration.ofMillis(50));
        assertThat(result.hasError()).isTrue();
        assertThat(result.error().cause()).isInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void invokeRespectsThreadInterrupt() throws Exception {
        java.util.concurrent.atomic.AtomicReference<AgentResult> ref = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);

        Agent slow = ctx -> {
            entered.countDown();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return AgentResult.ofText("done");
        };
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", slow)
                .addNode("b", ctx -> AgentResult.ofText("b"))
                .addEdge("a", "b")
                .build();

        Thread t = new Thread(() -> ref.set(graph.invoke(AgentContext.of("go"))));
        t.start();
        entered.await();
        t.interrupt();
        t.join(2000);

        assertThat(ref.get().hasError()).isTrue();
    }

    @Test
    void agentGraphCanBeUsedAsNodeInAnotherGraph() {
        AgentGraph inner = AgentGraph.builder()
                .name("inner")
                .addNode("i1", ctx -> AgentResult.ofText("inner-1"))
                .addNode("i2", ctx -> AgentResult.ofText("inner-2"))
                .addEdge("i1", "i2")
                .build();

        AgentGraph outer = AgentGraph.builder()
                .name("outer")
                .addNode("before", ctx -> AgentResult.ofText("before"))
                .addNode("sub", inner)
                .addNode("after", ctx -> AgentResult.ofText("after"))
                .addEdge("before", "sub")
                .addEdge("sub", "after")
                .build();

        AgentResult result = outer.invoke(AgentContext.of("go"));
        assertThat(result.text()).isEqualTo("after");
    }

    private static Agent trace(String tag) {
        return context -> AgentResult.builder()
                .text(tag)
                .stateUpdates(Map.of(TRACE, appendTrace(context, tag)))
                .build();
    }

    private static String appendTrace(AgentContext ctx, String tag) {
        String current = ctx.get(TRACE);
        return current == null ? tag : current + "->" + tag;
    }

    @Test
    void linearGraphRunsNodesInOrder() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", trace("a"))
                .addNode("b", trace("b"))
                .addNode("c", trace("c"))
                .addEdge("a", "b")
                .addEdge("b", "c")
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("c");
        assertThat(result.stateUpdates().get(TRACE)).isEqualTo("a->b->c");
    }

    @Test
    void conditionalEdgeRouts() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("start", trace("start"))
                .addNode("branchA", trace("A"))
                .addNode("branchB", trace("B"))
                .addEdge(Edge.conditional("start",
                        ctx -> "A".equals(ctx.get(TRACE)) || "start".equals(ctx.get(TRACE)),
                        "branchA"))
                .addEdge("start", "branchB")
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("A");
    }

    @Test
    void failFastPropagatesError() {
        Agent failing = ctx -> {
            throw new RuntimeException("boom");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("fail", failing)
                .errorPolicy(ErrorPolicy.FAIL_FAST)
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        AgentError err = java.util.Objects.requireNonNull(result.error());
        assertThat(err.nodeName()).isEqualTo("fail");
        assertThat(err.cause()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void retryOncePolicyRetriesOnce() {
        AtomicInteger attempts = new AtomicInteger();
        Agent flaky = ctx -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                throw new RuntimeException("transient");
            }
            return AgentResult.ofText("ok-on-attempt-" + attempt);
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("flaky", flaky)
                .retryPolicy(RetryPolicy.once())
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("ok-on-attempt-2");
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void skipNodeContinuesOnError() {
        Agent failing = ctx -> {
            throw new RuntimeException("boom");
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("fail", failing)
                .addNode("recover", trace("recover"))
                .addEdge("fail", "recover")
                .errorPolicy(ErrorPolicy.SKIP_NODE)
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("recover");
    }

    @Test
    void cycleTerminatesOnMaxIterations() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("loop", trace("l"))
                .addEdge("loop", "loop")
                .maxIterations(3)
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        AgentError err = java.util.Objects.requireNonNull(result.error());
        assertThat(err.cause().getMessage()).contains("Max iterations");
    }

    @Test
    void builderRejectsEdgeToUnknownNode() {
        assertThatThrownBy(() -> AgentGraph.builder()
                .addNode("a", trace("a"))
                .addEdge("a", "ghost")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void listenerReceivesLifecycleEvents() {
        AtomicInteger enters = new AtomicInteger();
        AtomicInteger exits = new AtomicInteger();
        AtomicInteger completes = new AtomicInteger();

        AgentListener listener = new AgentListener() {
            @Override
            public void onNodeEnter(String graphName, String nodeName, AgentContext context) {
                enters.incrementAndGet();
            }
            @Override
            public void onNodeExit(String graphName, String nodeName, AgentResult result, long durationNanos) {
                exits.incrementAndGet();
            }
            @Override
            public void onGraphComplete(String graphName, AgentResult result) {
                completes.incrementAndGet();
            }
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("a", trace("a"))
                .addNode("b", trace("b"))
                .addEdge("a", "b")
                .listener(listener)
                .build();

        graph.invoke(AgentContext.empty());
        assertThat(enters.get()).isEqualTo(2);
        assertThat(exits.get()).isEqualTo(2);
        assertThat(completes.get()).isEqualTo(1);
    }

    @Test
    void invokeStreamForwardsPerNodeTokens() {
        Agent streamingAgent = new Agent() {
            @Override
            public AgentResult execute(AgentContext context) {
                return AgentResult.ofText("hello world");
            }

            @Override
            public Flux<AgentEvent> executeStream(AgentContext context) {
                return Flux.just(
                        AgentEvent.token("hello "),
                        AgentEvent.token("world"),
                        AgentEvent.completed(AgentResult.ofText("hello world")));
            }
        };

        AgentGraph graph = AgentGraph.builder()
                .addNode("stream", streamingAgent)
                .build();

        StepVerifier.create(graph.invokeStream(AgentContext.empty()))
                .expectNextMatches(e -> e instanceof AgentEvent.Token t && "hello ".equals(t.chunk()))
                .expectNextMatches(e -> e instanceof AgentEvent.Token t && "world".equals(t.chunk()))
                .expectNextMatches(e -> e instanceof AgentEvent.Completed c
                        && "hello world".equals(c.result().text()))
                .verifyComplete();
    }

    @Test
    void invokeStreamEmitsTransitionsAndCompletion() {
        AgentGraph graph = AgentGraph.builder()
                .addNode("a", trace("a"))
                .addNode("b", trace("b"))
                .addEdge("a", "b")
                .build();

        StepVerifier.create(graph.invokeStream(AgentContext.empty()))
                .expectNextMatches(e -> e instanceof AgentEvent.NodeTransition t
                        && t.from().equals("a") && t.to().equals("b"))
                .expectNextMatches(e -> e instanceof AgentEvent.Completed c
                        && "b".equals(c.result().text()))
                .verifyComplete();
    }

    @Test
    void errorResultInNodeIsReportedAsFailure() {
        Agent returnsError = ctx -> AgentResult.failed(AgentError.of("x", new IllegalArgumentException("bad")));

        AgentGraph graph = AgentGraph.builder()
                .addNode("x", returnsError)
                .errorPolicy(ErrorPolicy.FAIL_FAST)
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.hasError()).isTrue();
    }

    @Test
    void onResultEdgeRoutesBasedOnLastResultText() {
        Agent classify = ctx -> AgentResult.ofText("needs-review");
        Agent review = ctx -> AgentResult.ofText("reviewed");
        Agent ship = ctx -> AgentResult.ofText("shipped");

        AgentGraph graph = AgentGraph.builder()
                .addNode("classify", classify)
                .addNode("review", review)
                .addNode("ship", ship)
                .addEdge(Edge.onResult("classify",
                        (c, r) -> "needs-review".equals(r.text()), "review"))
                .addEdge(Edge.direct("classify", "ship"))
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("reviewed");
    }

    @Test
    void onResultEdgeFallsThroughToDirectWhenPredicateFalse() {
        Agent classify = ctx -> AgentResult.ofText("ok");
        Agent ship = ctx -> AgentResult.ofText("shipped");

        AgentGraph graph = AgentGraph.builder()
                .addNode("classify", classify)
                .addNode("ship", ship)
                .addEdge(Edge.onResult("classify",
                        (c, r) -> "needs-review".equals(r.text()), "classify"))
                .addEdge(Edge.direct("classify", "ship"))
                .build();

        AgentResult result = graph.invoke(AgentContext.empty());
        assertThat(result.text()).isEqualTo("shipped");
    }

    @Test
    void exposesTopologyForIntrospection() {
        Agent a = ctx -> AgentResult.ofText("a");
        Agent b = ctx -> AgentResult.ofText("b");
        Agent c = ctx -> AgentResult.ofText("c");

        AgentGraph graph = AgentGraph.builder()
                .addNode("a", a)
                .addNode("b", b)
                .addNode("c", c)
                .addEdge("a", "b")
                .addEdge(Edge.conditional("b", ctx -> true, "c"))
                .build();

        assertThat(graph.entryNode()).isEqualTo("a");
        assertThat(graph.nodeNames()).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(graph.edges()).hasSize(2);
        assertThat(graph.edges().get(0).from()).isEqualTo("a");
        assertThat(graph.edges().get(0).to()).isEqualTo("b");
        assertThat(graph.edges().get(1)).isInstanceOf(Edge.Conditional.class);
    }
}
