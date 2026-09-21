package io.github.datallmhub.agentflow4j.samples;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.RetryPolicy;

/**
 * Research Squad example (§8 of the spec).
 *
 * <p>A four-stage linear graph: coordinate → research → analyze → write.
 * Each stage is implemented as an {@link Agent} (in production these are
 * {@code ExecutorAgent}s wrapping a {@code ChatClient}; here they are passed
 * in so the example is testable without a real LLM).
 */
public final class ResearchSquad {

    private ResearchSquad() {}

    public static AgentGraph build(Agent coordinator, Agent research, Agent analyze, Agent write) {
        return AgentGraph.builder()
                .name("research-squad")
                .addNode("coordinate", coordinator)
                .addNode("research", research)
                .addNode("analyze", analyze)
                .addNode("write", write)
                .addEdge("coordinate", "research")
                .addEdge("research", "analyze")
                .addEdge("analyze", "write")
                .retryPolicy(RetryPolicy.once())
                .build();
    }
}
