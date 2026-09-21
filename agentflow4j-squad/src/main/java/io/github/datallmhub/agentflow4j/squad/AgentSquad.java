package io.github.datallmhub.agentflow4j.squad;

import org.springframework.ai.chat.client.ChatClient;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.Experimental;

import java.util.UUID;

/**
 * A zero-friction facade for building and running a multi-agent squad.
 */
@Experimental
public final class AgentSquad {

    private final CoordinatorAgent coordinator;

    private AgentSquad(CoordinatorAgent coordinator) {
        this.coordinator = coordinator;
    }

    public static Builder with(ChatClient chatClient) {
        return new Builder(chatClient);
    }

    public String ask(String question) {
        var result = coordinator.execute(AgentContext.of(question));
        
        if (result.hasError()) {
            throw new IllegalStateException("Squad failed to answer: " + result.error().cause().getMessage());
        }
        
        return result.text();
    }

    public static final class Builder {
        private final ChatClient chatClient;
        private final CoordinatorAgent.Builder coordinatorBuilder = CoordinatorAgent.builder();

        private Builder(ChatClient chatClient) {
            this.chatClient = chatClient;
        }

        public Builder agent(String name, String systemPrompt) {
            coordinatorBuilder.executor(name, ExecutorAgent.builder()
                    .chatClient(this.chatClient)
                    .name(name)
                    .systemPrompt(systemPrompt)
                    .build());
            return this;
        }

        public Builder agent(String systemPrompt) {
            return agent("agent-" + UUID.randomUUID().toString().substring(0, 8), systemPrompt);
        }

        public String ask(String question) {
            return build().ask(question);
        }

        public AgentSquad build() {
            coordinatorBuilder.routingStrategy(RoutingStrategy.llmDriven(this.chatClient));
            return new AgentSquad(coordinatorBuilder.build());
        }
    }
}
