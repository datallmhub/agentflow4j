package io.github.datallmhub.agentflow4j.samples;

import java.util.Map;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.Edge;
import io.github.datallmhub.agentflow4j.graph.RetryPolicy;

/**
 * 03 — Advanced: conditional routing, typed state, error policy, listener.
 *
 * <p>A graph that researches → analyzes → conditionally loops back if
 * confidence is too low → writes the final report.
 *
 * <p>Expected output:
 * <pre>
 * === Advanced Graph with Conditional Routing ===
 *
 * [listener]  ENTER research
 * [research]  Gathering data on AI agents...
 * [listener]  EXIT  research (2ms)
 * [listener]  → research → analyze
 * [listener]  ENTER analyze
 * [analyze]   Confidence: 0.50 — needs more research
 * [listener]  EXIT  analyze (0ms)
 * [listener]  → analyze → research
 * [listener]  ENTER research
 * [research]  Gathering data on AI agents...
 * [listener]  EXIT  research (0ms)
 * [listener]  → research → analyze
 * [listener]  ENTER analyze
 * [analyze]   Confidence: 0.90 — analysis complete
 * [listener]  EXIT  analyze (0ms)
 * [listener]  → analyze → write
 * [listener]  ENTER write
 * [write]     Writing final report...
 * [listener]  EXIT  write (0ms)
 * [listener]  ✓ Graph complete
 *
 * Final report:
 *   AI agent frameworks enable multi-step LLM workflows.
 *   Confidence: 0.90. Research iterations: 2.
 * </pre>
 */
public final class AdvancedGraphDemo {

    static final StateKey<Double> CONFIDENCE = StateKey.of("confidence", Double.class);
    static final StateKey<Integer> ITERATIONS = StateKey.of("iterations", Integer.class);

    public static void main(String[] args) {
        System.out.println("=== Advanced Graph with Conditional Routing ===");
        System.out.println();

        // Research agent — gathers data
        Agent researcher = ctx -> {
            System.out.println("[research]  Gathering data on AI agents...");
            int iter = ctx.get(ITERATIONS) != null ? ctx.get(ITERATIONS) + 1 : 1;
            return AgentResult.builder()
                    .text("Raw data collected (iteration " + iter + ")")
                    .stateUpdates(Map.of(ITERATIONS, iter))
                    .completed(true)
                    .build();
        };

        // Analyzer — sets confidence score, may trigger re-research
        Agent analyzer = ctx -> {
            int iter = ctx.get(ITERATIONS) != null ? ctx.get(ITERATIONS) : 1;
            double confidence = iter >= 2 ? 0.90 : 0.50;
            System.out.printf("[analyze]   Confidence: %.2f — %s%n",
                    confidence,
                    confidence >= 0.7 ? "analysis complete" : "needs more research");
            return AgentResult.builder()
                    .text("Analysis done, confidence=" + confidence)
                    .stateUpdates(Map.of(CONFIDENCE, confidence))
                    .completed(true)
                    .build();
        };

        // Writer — produces the final report
        Agent writer = ctx -> {
            System.out.println("[write]     Writing final report...");
            Double conf = ctx.get(CONFIDENCE);
            Integer iter = ctx.get(ITERATIONS);
            return AgentResult.ofText(
                    "AI agent frameworks enable multi-step LLM workflows.\n"
                  + "  Confidence: " + String.format("%.2f", conf)
                  + ". Research iterations: " + iter + ".");
        };

        // Logging listener
        AgentListener logger = new AgentListener() {
            @Override
            public void onNodeEnter(String g, String node, AgentContext ctx) {
                System.out.println("[listener]  ENTER " + node);
            }
            @Override
            public void onNodeExit(String g, String node, AgentResult r, long ns) {
                System.out.printf("[listener]  EXIT  %s (%dms)%n", node, ns / 1_000_000);
            }
            @Override
            public void onTransition(String g, String from, String to) {
                System.out.println("[listener]  → " + from + " → " + to);
            }
            @Override
            public void onGraphComplete(String g, AgentResult r) {
                System.out.println("[listener]  ✓ Graph complete");
            }
        };

        // Build the graph with a conditional loop
        AgentGraph graph = AgentGraph.builder()
                .name("research-loop")
                .addNode("research", researcher)
                .addNode("analyze", analyzer)
                .addNode("write", writer)
                .addEdge("research", "analyze")
                // If confidence < 0.7, loop back to research
                .addEdge(Edge.conditional("analyze",
                        ctx -> {
                            Double c = ctx.get(CONFIDENCE);
                            return c != null && c < 0.7;
                        },
                        "research"))
                // Otherwise, fall through to write
                .addEdge("analyze", "write")
                .retryPolicy(RetryPolicy.once())
                .listener(logger)
                .build();

        // Run
        AgentResult result = graph.invoke(AgentContext.of("Analyze AI agent frameworks"));

        System.out.println();
        System.out.println("Final report:");
        System.out.println("  " + result.text());
    }
}
