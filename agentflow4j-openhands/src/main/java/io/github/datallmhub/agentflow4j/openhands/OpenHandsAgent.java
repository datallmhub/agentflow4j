package io.github.datallmhub.agentflow4j.openhands;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.Experimental;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.AgentUsage;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delegates a coding task to OpenHands from a graph node, and reports back as
 * any other agent: a result when the task is done, an interrupt when a human is
 * needed, a failure when it broke.
 *
 * <p>The node never starts a second conversation for the same run: the
 * conversation id lives in the graph's state
 * ({@link OpenHandsKeys#CONVERSATION_ID}), so a rerun, a resume or a retry
 * polls the existing conversation instead of paying for a new one.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>{@link Mode#ASYNC} (the default): the node starts the conversation and
 *       returns {@code openhands.running}, an interrupt. The graph checkpoints
 *       and the thread is free; a later {@code resume} polls once and either
 *       interrupts again or returns the result. A task that takes hours costs
 *       no thread and survives a restart.</li>
 *   <li>{@link Mode#SYNC}: the node polls until the conversation is terminal or
 *       {@code maxWait} elapses. Simpler, but it holds a thread.</li>
 * </ul>
 *
 * <pre>{@code
 * OpenHandsAgent coder = OpenHandsAgent.builder()
 *         .client(OpenHandsClient.cloud(apiKey))
 *         .repository("acme/billing")
 *         .task(ctx -> ctx.get(TICKET))
 *         .maxCost(5.00)
 *         .build();
 * }</pre>
 */
@Experimental
public final class OpenHandsAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(OpenHandsAgent.class);

    /** How the node waits for OpenHands. */
    public enum Mode { ASYNC, SYNC }

    private final String name;
    private final OpenHandsClient client;
    @Nullable private final String repository;
    private final Function<AgentContext, String> task;
    private final Mode mode;
    private final Duration pollInterval;
    private final Duration startTimeout;
    private final Duration maxWait;
    @Nullable private final Double maxCost;

    private OpenHandsAgent(Builder b) {
        this.name = b.name;
        this.client = Objects.requireNonNull(b.client, "client");
        this.repository = b.repository;
        this.task = Objects.requireNonNull(b.task, "task");
        this.mode = b.mode;
        this.pollInterval = b.pollInterval;
        this.startTimeout = b.startTimeout;
        this.maxWait = b.maxWait;
        this.maxCost = b.maxCost;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        String conversationId = context.get(OpenHandsKeys.CONVERSATION_ID);
        Map<StateKey<?>, Object> state = new LinkedHashMap<>();

        if (conversationId == null) {
            OpenHandsStartTask started = start(context);
            conversationId = started.conversationId();
            state.put(OpenHandsKeys.CONVERSATION_ID, conversationId);
            if (started.sandboxId() != null) {
                state.put(OpenHandsKeys.SANDBOX_ID, started.sandboxId());
            }
            log.info("openhands.started: node={} conversation={} repository={}", name, conversationId, repository);
        }
        else {
            log.info("openhands.polling: node={} conversation={}", name, conversationId);
        }

        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            OpenHandsConversation conversation = client.conversation(conversationId);
            state.put(OpenHandsKeys.COST, conversation.accumulatedCost());
            AgentResult result = report(conversation, state);
            if (result != null) {
                return result;
            }
            if (mode == Mode.ASYNC) {
                return interrupted("openhands.running:" + conversationId, conversation, state);
            }
            if (System.nanoTime() > deadline) {
                return failed(conversationId, new java.util.concurrent.TimeoutException(
                        "OpenHands conversation " + conversationId + " still " + conversation.status()
                                + " after " + maxWait), state);
            }
            sleep(pollInterval);
        }
    }

    /**
     * Turns a conversation into a node result, or {@code null} while the agent
     * is still working.
     */
    @Nullable
    private AgentResult report(OpenHandsConversation conversation, Map<StateKey<?>, Object> state) {
        if (conversation.branch() != null) {
            state.put(OpenHandsKeys.BRANCH, conversation.branch());
        }
        if (conversation.pullRequest() != null) {
            state.put(OpenHandsKeys.PULL_REQUEST, conversation.pullRequest());
        }

        if (maxCost != null && conversation.accumulatedCost() > maxCost) {
            // Stop paying for a run that blew its cap, and let the graph's budget
            // handling surface it as an over-budget interrupt.
            pauseQuietly(conversation);
            String reason = "budget.exceeded:" + name;
            log.warn("openhands.over_budget: node={} conversation={} spent={} cap={}",
                    name, conversation.id(), conversation.accumulatedCost(), maxCost);
            return AgentResult.builder()
                    .interrupt(new InterruptRequest(reason, conversation))
                    .stateUpdates(state)
                    .usage(usageOf(conversation))
                    .build();
        }

        if (conversation.sandboxLost()) {
            return failed(conversation.id(), new IllegalStateException(
                    "OpenHands sandbox " + conversation.sandboxId() + " is " + conversation.sandboxStatus()), state);
        }

        return switch (conversation.status()) {
            case FINISHED -> AgentResult.builder()
                    .text(conversation.summary())
                    .stateUpdates(state)
                    .usage(usageOf(conversation))
                    .completed(true)
                    .build();
            case ERROR -> failed(conversation.id(),
                    new IllegalStateException("OpenHands conversation " + conversation.id() + " failed"), state);
            // Both need a human in the OpenHands UI, so they surface as interrupts.
            case STUCK -> interrupted("openhands.stuck:" + conversation.id(), conversation, state);
            case WAITING_FOR_CONFIRMATION ->
                    interrupted("openhands.confirmation:" + conversation.id(), conversation, state);
            case STARTING, IDLE, RUNNING, PAUSED, UNKNOWN -> null;
        };
    }

    private OpenHandsStartTask start(AgentContext context) {
        String prompt = task.apply(context);
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("The task for OpenHands node '" + name + "' is empty");
        }
        OpenHandsStartTask started = client.startConversation(prompt, repository);
        long deadline = System.nanoTime() + startTimeout.toNanos();
        while (!started.isReady()) {
            if (started.status() == OpenHandsStatus.ERROR) {
                throw new OpenHandsException("OpenHands could not start a conversation for node '" + name + "'",
                        0, null);
            }
            if (System.nanoTime() > deadline) {
                throw new OpenHandsException("OpenHands did not start a conversation for node '" + name
                        + "' within " + startTimeout, 0, null);
            }
            sleep(pollInterval);
            started = client.startTask(started.id());
        }
        return started;
    }

    private AgentResult interrupted(String reason, OpenHandsConversation conversation,
                                   Map<StateKey<?>, Object> state) {
        return AgentResult.builder()
                .interrupt(new InterruptRequest(reason, conversation))
                .stateUpdates(state)
                .usage(usageOf(conversation))
                .build();
    }

    private AgentResult failed(String conversationId, Throwable cause, Map<StateKey<?>, Object> state) {
        log.error("openhands.failed: node={} conversation={}", name, conversationId, cause);
        return AgentResult.builder()
                .error(AgentError.of(name, cause))
                .stateUpdates(state)
                .build();
    }

    private void pauseQuietly(OpenHandsConversation conversation) {
        String sandboxId = conversation.sandboxId();
        if (sandboxId == null) {
            return;
        }
        try {
            client.pauseSandbox(sandboxId);
        }
        catch (RuntimeException ex) {
            log.warn("openhands.pause_failed: sandbox={}", sandboxId, ex);
        }
    }

    private static AgentUsage usageOf(OpenHandsConversation conversation) {
        return AgentUsage.of(conversation.promptTokens(), conversation.completionTokens());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0L, duration.toMillis()));
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenHandsException("Interrupted while waiting for OpenHands", ex);
        }
    }

    public static final class Builder {
        private String name = "openhands";
        private OpenHandsClient client;
        @Nullable private String repository;
        private Function<AgentContext, String> task = OpenHandsAgent::lastUserMessage;
        private Mode mode = Mode.ASYNC;
        private Duration pollInterval = Duration.ofSeconds(15);
        private Duration startTimeout = Duration.ofMinutes(5);
        private Duration maxWait = Duration.ofHours(1);
        @Nullable private Double maxCost;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder client(OpenHandsClient client) {
            this.client = client;
            return this;
        }

        /** The repository OpenHands clones; without one the agent works on an empty sandbox. */
        public Builder repository(String repository) {
            this.repository = repository;
            return this;
        }

        /** How the task is read from the context; by default the last user message. */
        public Builder task(Function<AgentContext, String> task) {
            this.task = Objects.requireNonNull(task, "task");
            return this;
        }

        public Builder mode(Mode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = requirePositive(pollInterval, "pollInterval");
            return this;
        }

        /** How long to wait for OpenHands to prepare the sandbox and the repository. */
        public Builder startTimeout(Duration startTimeout) {
            this.startTimeout = requirePositive(startTimeout, "startTimeout");
            return this;
        }

        /** {@link Mode#SYNC} only: how long the node polls before giving up. */
        public Builder maxWait(Duration maxWait) {
            this.maxWait = requirePositive(maxWait, "maxWait");
            return this;
        }

        /**
         * Pauses the sandbox and interrupts the run once OpenHands reports a
         * cost above this cap, in the currency OpenHands bills in.
         */
        public Builder maxCost(double maxCost) {
            if (maxCost <= 0) {
                throw new IllegalArgumentException("maxCost must be > 0");
            }
            this.maxCost = maxCost;
            return this;
        }

        public OpenHandsAgent build() {
            return new OpenHandsAgent(this);
        }

        private static Duration requirePositive(Duration value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(field + " must be > 0");
            }
            return value;
        }
    }

    private static String lastUserMessage(AgentContext context) {
        for (int i = context.messages().size() - 1; i >= 0; i--) {
            if (context.messages().get(i) instanceof org.springframework.ai.chat.messages.UserMessage user) {
                return user.getText();
            }
        }
        return "";
    }
}
