package io.github.datallmhub.agentflow4j.graph;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

record AgentNode(
        String name,
        Agent agent,
        @Nullable RetryPolicy retryPolicy,
        @Nullable CircuitBreakerPolicy circuitBreaker) implements Node {
    @Override
    public AgentResult execute(AgentContext context) {
        return agent.execute(context);
    }

    @Override
    public Flux<AgentEvent> executeStream(AgentContext context) {
        return agent.executeStream(context);
    }
}
