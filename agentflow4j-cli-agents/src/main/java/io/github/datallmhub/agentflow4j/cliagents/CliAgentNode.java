package io.github.datallmhub.agentflow4j.cliagents;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.Experimental;
import org.springaicommunity.agents.model.AgentModel;
import org.springaicommunity.agents.model.AgentOptions;
import org.springaicommunity.agents.model.AgentResponse;
import org.springaicommunity.agents.model.AgentTaskRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.jspecify.annotations.Nullable;

/**
 * Adapts a {@code spring-ai-community} {@link AgentModel} (the SPI behind
 * {@code AgentClient}) into a agentflow4j {@link Agent} so external CLI
 * agents — Claude Code, Codex, Gemini CLI, Amazon Q, Amp, Qwen, SWE — can
 * become nodes inside an {@code AgentGraph}.
 *
 * <p>The node derives the {@code goal} text from the {@link AgentContext}
 * (last user message by default, configurable), forwards a fixed working
 * directory, and maps the returned {@link AgentResponse} back into an
 * {@link AgentResult}. The raw response is exposed via
 * {@code AgentResult.structuredOutput()} for downstream nodes that need
 * provider-specific metadata.
 *
 * <p>Failures are surfaced as failed {@code AgentResult}s rather than
 * exceptions so the graph's {@code ErrorPolicy} / {@code RetryPolicy} can
 * intervene.
 */
@Experimental
public final class CliAgentNode implements Agent {

    private final String name;
    private final AgentModel model;
    private final Path workingDirectory;
    private final Function<AgentContext, String> goalExtractor;
    @Nullable private final AgentOptions defaultOptions;

    private CliAgentNode(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.model = Objects.requireNonNull(b.model, "model");
        this.workingDirectory = Objects.requireNonNull(b.workingDirectory, "workingDirectory");
        this.goalExtractor = Objects.requireNonNull(b.goalExtractor, "goalExtractor");
        this.defaultOptions = b.defaultOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        Objects.requireNonNull(context, "context");
        String goal;
        try {
            goal = goalExtractor.apply(context);
        } catch (RuntimeException e) {
            return AgentResult.failed(AgentError.of(name, e));
        }
        if (goal == null || goal.isBlank()) {
            return AgentResult.failed(AgentError.of(name,
                    new IllegalStateException("goalExtractor returned a blank goal for " + name)));
        }

        AgentTaskRequest.Builder req = AgentTaskRequest.builder(goal, workingDirectory);
        if (defaultOptions != null) {
            req.options(defaultOptions);
        }

        AgentResponse response;
        try {
            response = model.call(req.build());
        } catch (RuntimeException e) {
            return AgentResult.failed(AgentError.of(name, e));
        }

        if (!response.isSuccessful()) {
            return AgentResult.failed(AgentError.of(name,
                    new RuntimeException("CLI agent did not complete successfully: "
                            + response.getText())));
        }

        return AgentResult.builder()
                .text(response.getText())
                .structuredOutput(response)
                .completed(true)
                .build();
    }

    /**
     * Default goal extractor: the text of the last user message in the context.
     * Fails (returns {@code null}) if no user message is present — the node
     * surfaces that as a failed {@code AgentResult}.
     */
    public static String lastUserMessageText(AgentContext context) {
        Message last = null;
        for (Message m : context.messages()) {
            if (m.getMessageType() == MessageType.USER) {
                last = m;
            }
        }
        return last == null ? null : last.getText();
    }

    public static final class Builder {
        private String name = "cli-agent";
        private AgentModel model;
        private Path workingDirectory;
        private Function<AgentContext, String> goalExtractor = CliAgentNode::lastUserMessageText;
        @Nullable private AgentOptions defaultOptions;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder model(AgentModel model) {
            this.model = Objects.requireNonNull(model, "model");
            return this;
        }

        public Builder workingDirectory(Path workingDirectory) {
            this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
            return this;
        }

        public Builder goalExtractor(Function<AgentContext, String> goalExtractor) {
            this.goalExtractor = Objects.requireNonNull(goalExtractor, "goalExtractor");
            return this;
        }

        public Builder fixedGoal(String goal) {
            Objects.requireNonNull(goal, "goal");
            this.goalExtractor = ctx -> goal;
            return this;
        }

        public Builder defaultOptions(@Nullable AgentOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public CliAgentNode build() {
            return new CliAgentNode(this);
        }
    }
}
