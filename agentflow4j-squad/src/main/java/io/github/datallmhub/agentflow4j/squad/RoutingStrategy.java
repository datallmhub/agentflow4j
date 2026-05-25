package io.github.datallmhub.agentflow4j.squad;

import java.util.Set;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import org.springframework.ai.chat.client.ChatClient;

@FunctionalInterface
public interface RoutingStrategy {

    String selectExecutor(AgentContext context, Set<String> availableExecutors);

    static RoutingStrategy first() {
        return (ctx, names) -> {
            if (names.isEmpty()) {
                throw new IllegalStateException("No executors available for routing");
            }
            return names.iterator().next();
        };
    }

    static RoutingStrategy fixed(String executorName) {
        return (ctx, names) -> {
            if (!names.contains(executorName)) {
                throw new IllegalStateException("Fixed executor not registered: " + executorName);
            }
            return executorName;
        };
    }

    static RoutingStrategy llmDriven(ChatClient chatClient) {
        return new LlmRoutingStrategy(chatClient);
    }

    /**
     * Deterministic, cost-aware routing: send work to {@code premium} while
     * the remaining budget at {@code scope} is {@code >= threshold}, and
     * switch to {@code fallback} once it drops below. See
     * {@link BudgetAwareRouter} for the full rationale and wiring.
     */
    static RoutingStrategy budgetAware(BudgetPolicy budget,
                                       BudgetPolicy.Scope scope,
                                       double threshold,
                                       String premium,
                                       String fallback) {
        return new BudgetAwareRouter(budget, scope, threshold, premium, fallback);
    }

    /**
     * Marker for {@link Agent}s that already embed their own routing logic
     * and should not be wrapped in a default strategy.
     */
    RoutingStrategy LLM_DRIVEN_MARKER = (ctx, names) -> {
        throw new IllegalStateException(
                "LLM_DRIVEN_MARKER is a sentinel; use RoutingStrategy.llmDriven(ChatClient) instead");
    };
}
