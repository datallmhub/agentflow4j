package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public final class AgentGraph implements Agent {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    private final String name;
    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final String entryNode;
    private final ErrorPolicy errorPolicy;
    private final RetryPolicy retryPolicy;
    private final BudgetPolicy budgetPolicy;
    private final StatePolicy statePolicy;
    private final ApprovalGate approvalGate;
    private final int maxIterations;
    private final List<AgentListener> listeners;
    @Nullable
    private final CheckpointStore checkpointStore;
    @Nullable
    private final RunLogStore runLogStore;

    private AgentGraph(Builder b) {
        this.name = b.name;
        this.nodes = Map.copyOf(b.nodes);
        this.edges = List.copyOf(b.edges);
        this.entryNode = Objects.requireNonNull(b.entryNode,
                "entryNode must be set (first addNode is used by default)");
        this.errorPolicy = b.errorPolicy;
        this.retryPolicy = b.retryPolicy != null ? b.retryPolicy
                : (b.errorPolicy == ErrorPolicy.RETRY_ONCE ? RetryPolicy.once() : RetryPolicy.none());
        this.budgetPolicy = b.budgetPolicy;
        this.statePolicy = b.statePolicy;
        this.approvalGate = b.approvalGate;
        this.maxIterations = b.maxIterations;
        this.listeners = List.copyOf(b.listeners);
        this.checkpointStore = b.checkpointStore;
        this.runLogStore = b.runLogStore;

        validate();
    }

    private void validate() {
        if (!nodes.containsKey(entryNode)) {
            throw new IllegalStateException("Entry node '" + entryNode + "' is not registered");
        }
        for (Edge edge : edges) {
            if (!nodes.containsKey(edge.from())) {
                throw new IllegalStateException("Edge from unknown node: " + edge.from());
            }
            if (!nodes.containsKey(edge.to())) {
                throw new IllegalStateException("Edge to unknown node: " + edge.to());
            }
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    /** The entry node the graph starts from. */
    public String entryNode() {
        return entryNode;
    }

    /** Node names declared in this graph, for introspection / visualization. */
    public java.util.Set<String> nodeNames() {
        return nodes.keySet();
    }

    /**
     * The edges of this graph, for introspection / visualization. The returned
     * list is immutable; edge predicates are not exposed beyond {@code from()}
     * / {@code to()}.
     */
    public List<Edge> edges() {
        return edges;
    }

    public AgentResult invoke(AgentContext initial) {
        Objects.requireNonNull(initial, "initial");
        return run(initial, entryNode, 0, null, null);
    }

    public AgentResult invoke(AgentContext initial, Duration timeout) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        return run(initial, entryNode, 0, null, deadline);
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return invoke(context);
    }

    @Override
    public Flux<AgentEvent> executeStream(AgentContext context) {
        return invokeStream(context);
    }

    /**
     * Runs the graph under an explicit {@code runId}. The id identifies the
     * run for both checkpointing (if a {@link CheckpointStore} is configured)
     * and the {@link RunLogStore} (if one is configured). Neither is required:
     * with neither store, this behaves like {@link #invoke(AgentContext)} but
     * with a caller-chosen id usable to query {@link #runLog(String)}.
     */
    public AgentResult invoke(AgentContext initial, String runId) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(runId, "runId");
        if (checkpointStore != null) {
            checkpointStore.save(new Checkpoint(runId, entryNode, initial, 0, null));
        }
        return run(initial, entryNode, 0, runId, null);
    }

    public AgentResult resume(String runId, Message... additional) {
        Objects.requireNonNull(runId, "runId");
        CheckpointStore store = requireCheckpointStore();
        Checkpoint cp = store.load(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "No checkpoint found for runId=" + runId));
        AgentContext context = cp.context();
        if (additional != null && additional.length > 0) {
            context = context.withMessages(List.of(additional));
        }
        return run(context, cp.nextNode(), cp.iterations(), runId, null);
    }

    private CheckpointStore requireCheckpointStore() {
        if (checkpointStore == null) {
            throw new IllegalStateException(
                    "AgentGraph has no CheckpointStore; configure one via Builder.checkpointStore(...)");
        }
        return checkpointStore;
    }

    private AgentResult run(AgentContext context, String startNode,
                            int startIterations, @Nullable String runId,
                            @Nullable Long deadlineNanos) {
        log.info("graph.start: graph={}", name);
        String currentNode = startNode;
        AgentResult lastResult = null;
        int iterations = startIterations;
        CheckpointStore store = checkpointStore;
        RunRecorder recorder = RunRecorder.forRun(
                runId != null ? runId : java.util.UUID.randomUUID().toString(), runLogStore);

        while (currentNode != null) {
            if (Thread.currentThread().isInterrupted()) {
                AgentError err = AgentError.of(currentNode,
                        new InterruptedException("Graph execution interrupted"));
                recorder.error(currentNode, "interrupted");
                notifyError(currentNode, err);
                return AgentResult.failed(err);
            }
            if (deadlineNanos != null && System.nanoTime() > deadlineNanos) {
                AgentError err = AgentError.of(currentNode,
                        new java.util.concurrent.TimeoutException(
                                "Graph exceeded timeout before entering node '" + currentNode + "'"));
                recorder.error(currentNode, "timeout");
                notifyError(currentNode, err);
                return AgentResult.failed(err);
            }
            if (++iterations > maxIterations) {
                AgentError err = AgentError.of(currentNode,
                        new IllegalStateException("Max iterations exceeded: " + maxIterations));
                recorder.error(currentNode, "max iterations exceeded");
                notifyError(currentNode, err);
                return AgentResult.failed(err);
            }

            Node node = nodes.get(currentNode);
            recorder.enter(currentNode);
            notifyEnter(currentNode, context);

            AgentResult approvalInterrupt = gateApproval(currentNode, context);
            if (approvalInterrupt != null) {
                recorder.approvalRequired(currentNode, approvalInterrupt.interrupt().reason());
                if (runId != null && store != null) {
                    store.save(new Checkpoint(runId, currentNode, context,
                            iterations - 1, approvalInterrupt.interrupt()));
                }
                notifyExit(currentNode, approvalInterrupt, 0L);
                recorder.complete("approval required at " + currentNode);
                notifyGraphComplete(approvalInterrupt);
                return approvalInterrupt;
            }

            NodeOutcome outcome = executeWithPolicy(node, context);
            outcome = enforceStatePolicy(currentNode, outcome);
            recorder.exit(currentNode, outcome.durationNanos);
            notifyExit(currentNode, outcome.result, outcome.durationNanos);

            if (outcome.result.hasError()) {
                AgentError err = outcome.result.error();
                if (err != null && err.cause() instanceof StatePolicyViolation spv) {
                    recorder.stateDenied(currentNode, spv.reason());
                } else {
                    recorder.error(currentNode, errorMessage(err));
                }
                notifyError(currentNode, err);
                if (errorPolicy == ErrorPolicy.FAIL_FAST) {
                    recorder.complete("failed at " + currentNode);
                    notifyGraphComplete(outcome.result);
                    return outcome.result;
                }
                lastResult = outcome.result;
            }
            else {
                context = context.applyResult(outcome.result);
                lastResult = outcome.result;
            }

            if (outcome.result.isInterrupted()) {
                String reason = outcome.result.interrupt().reason();
                if (reason.startsWith("budget.exceeded")) {
                    recorder.budgetExceeded(currentNode, reason);
                }
                if (runId != null && store != null) {
                    store.save(new Checkpoint(runId, currentNode, context,
                            iterations - 1, outcome.result.interrupt()));
                }
                recorder.complete("interrupted at " + currentNode + ": " + reason);
                notifyGraphComplete(outcome.result);
                return outcome.result;
            }

            String next = nextNode(currentNode, context, lastResult).orElse(null);

            if (runId != null && store != null) {
                if (next != null) {
                    store.save(new Checkpoint(runId, next, context, iterations, null));
                }
                else {
                    store.delete(runId);
                }
            }

            if (next != null) {
                recorder.transition(currentNode, next);
                notifyTransition(currentNode, next);
            }
            currentNode = next;
        }

        AgentResult finalResult = lastResult != null ? lastResult : AgentResult.ofText(null);
        recorder.complete("completed");
        notifyGraphComplete(finalResult);
        return finalResult;
    }

    private static String errorMessage(@Nullable AgentError error) {
        if (error == null) {
            return "error";
        }
        Throwable cause = error.cause();
        if (cause == null) {
            return "error";
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }

    /**
     * Returns the recorded execution log for {@code runId}, or an empty list
     * when no {@link RunLogStore} is configured or the run is unknown.
     */
    public List<AgentRunEvent> runLog(String runId) {
        Objects.requireNonNull(runId, "runId");
        return runLogStore == null ? List.of() : runLogStore.events(runId);
    }

    public Flux<AgentEvent> invokeStream(AgentContext initial) {
        // Flux.create runs the imperative loop (including toIterable() inside tryStream)
        // on the subscriber's thread. subscribeOn(boundedElastic) ensures that thread
        // is always blocking-capable, even when the caller is a Netty/WebFlux event loop.
        return Flux.<AgentEvent>create(sink -> {
            RunRecorder recorder = RunRecorder.forRun(
                    java.util.UUID.randomUUID().toString(), runLogStore);
            try {
                log.info("graph.start: graph={}", name);
                AgentContext context = initial;
                String currentNode = entryNode;
                AgentResult lastResult = null;
                String previousNode = null;
                int iterations = 0;

                while (currentNode != null) {
                    if (++iterations > maxIterations) {
                        AgentError err = AgentError.of(currentNode,
                                new IllegalStateException("Max iterations exceeded: " + maxIterations));
                        recorder.error(currentNode, "max iterations exceeded");
                        recorder.complete("failed at " + currentNode);
                        sink.next(AgentEvent.completed(AgentResult.failed(err)));
                        sink.complete();
                        return;
                    }

                    if (previousNode != null) {
                        recorder.transition(previousNode, currentNode);
                        sink.next(AgentEvent.transition(previousNode, currentNode));
                        notifyTransition(previousNode, currentNode);
                    }

                    Node node = nodes.get(currentNode);
                    recorder.enter(currentNode);
                    notifyEnter(currentNode, context);

                    NodeOutcome outcome = streamNodeWithPolicy(node, context, sink);
                    recorder.exit(currentNode, outcome.durationNanos);
                    notifyExit(currentNode, outcome.result, outcome.durationNanos);

                    if (outcome.result.hasError()) {
                        recorder.error(currentNode, errorMessage(outcome.result.error()));
                        notifyError(currentNode, outcome.result.error());
                        if (errorPolicy == ErrorPolicy.FAIL_FAST) {
                            recorder.complete("failed at " + currentNode);
                            sink.next(AgentEvent.completed(outcome.result));
                            sink.complete();
                            return;
                        }
                        lastResult = outcome.result;
                    }
                    else {
                        context = context.applyResult(outcome.result);
                        lastResult = outcome.result;
                    }

                    previousNode = currentNode;
                    currentNode = nextNode(currentNode, context, lastResult).orElse(null);
                }

                AgentResult finalResult = lastResult != null ? lastResult : AgentResult.ofText(null);
                recorder.complete("completed");
                notifyGraphComplete(finalResult);
                sink.next(AgentEvent.completed(finalResult));
                sink.complete();
            }
            catch (Throwable t) {
                sink.error(t);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private NodeOutcome streamNodeWithPolicy(Node node, AgentContext context,
                                             reactor.core.publisher.FluxSink<AgentEvent> sink) {
        long start = System.nanoTime();
        AgentResult result = null;
        RetryPolicy policy = effectivePolicy(node);
        int attempts = Math.max(1, policy.maxAttempts());
        for (int i = 0; i < attempts; i++) {
            AgentResult gate = gateBudget(node.name(), context);
            if (gate != null) {
                return new NodeOutcome(gate, System.nanoTime() - start);
            }
            result = tryStream(node, context, sink, i);
            if (!result.hasError()) {
                budgetPolicy.record(node.name(), result);
                break;
            }
            AgentError err = result.error();
            if (err == null || err.cause() == null) {
                break;
            }
            FailureClassification classification = policy.classify(err.cause());
            if (classification.category() == FailureCategory.OVER_BUDGET) {
                String reason = classification.reason() != null
                        ? classification.reason()
                        : "node '" + node.name() + "' signaled budget exhaustion";
                log.warn("Node '{}' over-budget during stream, interrupting: {}",
                        node.name(), reason);
                result = AgentResult.interrupted(
                        new InterruptRequest("budget.exceeded:" + node.name(), reason));
                break;
            }
            boolean canRetry = i < attempts - 1
                    && classification.category() == FailureCategory.TRANSIENT;
            if (!canRetry) {
                if (classification.category() == FailureCategory.PERMANENT) {
                    log.warn("Node '{}' failed (PERMANENT), no retry: {}",
                            node.name(),
                            classification.reason() != null
                                    ? classification.reason()
                                    : err.cause().getClass().getSimpleName());
                }
                break;
            }
            long delay = classification.retryAfter() != null
                    ? classification.retryAfter().toMillis()
                    : policy.computeDelayMs(i + 1);
            log.warn("Node '{}' failed during stream (TRANSIENT), retrying ({}/{}) after {}ms",
                    node.name(), i + 1, attempts - 1, delay);
            sleep(delay);
        }
        long duration = System.nanoTime() - start;

        AgentError err = result != null ? result.error() : null;
        if (err != null && errorPolicy == ErrorPolicy.SKIP_NODE) {
            log.warn("Node '{}' failed during stream, skipping (policy=SKIP_NODE)",
                    node.name(), err.cause());
        }
        return new NodeOutcome(result, duration);
    }

    private AgentResult tryStream(Node node, AgentContext context,
                                  reactor.core.publisher.FluxSink<AgentEvent> sink, int retryCount) {
        java.util.concurrent.atomic.AtomicReference<AgentResult> holder =
                new java.util.concurrent.atomic.AtomicReference<>();
        try {
            for (AgentEvent event : node.executeStream(context).toIterable()) {
                if (event instanceof AgentEvent.Completed completed) {
                    holder.set(completed.result());
                }
                else {
                    sink.next(event);
                }
            }
        }
        catch (Throwable t) {
            return AgentResult.failed(new AgentError(node.name(), t, retryCount));
        }
        AgentResult result = holder.get();
        if (result == null) {
            return AgentResult.failed(new AgentError(node.name(),
                    new IllegalStateException("Node stream did not emit a Completed event"),
                    retryCount));
        }
        AgentError err = result.error();
        if (err != null) {
            return AgentResult.failed(err.withRetryCount(retryCount));
        }
        return result;
    }

    private NodeOutcome executeWithPolicy(Node node, AgentContext context) {
        long start = System.nanoTime();
        AgentResult result = null;
        RetryPolicy policy = effectivePolicy(node);
        int attempts = Math.max(1, policy.maxAttempts());
        for (int i = 0; i < attempts; i++) {
            AgentResult gate = gateBudget(node.name(), context);
            if (gate != null) {
                return new NodeOutcome(gate, System.nanoTime() - start);
            }
            result = tryExecute(node, context, i);
            if (!result.hasError()) {
                budgetPolicy.record(node.name(), result);
                break;
            }
            AgentError err = result.error();
            if (err == null || err.cause() == null) {
                break;
            }
            FailureClassification classification = policy.classify(err.cause());
            if (classification.category() == FailureCategory.OVER_BUDGET) {
                String reason = classification.reason() != null
                        ? classification.reason()
                        : "node '" + node.name() + "' signaled budget exhaustion";
                log.warn("Node '{}' over-budget, interrupting: {}", node.name(), reason);
                result = AgentResult.interrupted(
                        new InterruptRequest("budget.exceeded:" + node.name(), reason));
                break;
            }
            boolean canRetry = i < attempts - 1
                    && classification.category() == FailureCategory.TRANSIENT;
            if (!canRetry) {
                if (classification.category() == FailureCategory.PERMANENT) {
                    log.warn("Node '{}' failed (PERMANENT), no retry: {}",
                            node.name(),
                            classification.reason() != null
                                    ? classification.reason()
                                    : err.cause().getClass().getSimpleName());
                }
                break;
            }
            long delay = classification.retryAfter() != null
                    ? classification.retryAfter().toMillis()
                    : policy.computeDelayMs(i + 1);
            log.warn("Node '{}' failed (TRANSIENT), retrying ({}/{}) after {}ms",
                    node.name(), i + 1, attempts - 1, delay);
            sleep(delay);
        }
        long duration = System.nanoTime() - start;

        AgentError err = result != null ? result.error() : null;
        if (err != null && errorPolicy == ErrorPolicy.SKIP_NODE) {
            log.warn("Node '{}' failed, skipping (policy=SKIP_NODE)", node.name(), err.cause());
        }

        return new NodeOutcome(result, duration);
    }

    /**
     * Consults the {@link ApprovalGate} before a node runs. Returns
     * {@code null} when execution may proceed, otherwise an interrupted
     * {@link AgentResult} that the caller resumes via
     * {@link #resumeWithApproval(String, String,
     * org.springframework.ai.chat.messages.Message...)}.
     */
    @Nullable
    private AgentResult gateApproval(String nodeName, AgentContext context) {
        if (approvalGate == ApprovalGate.NONE) {
            return null;
        }
        ApprovalGate.Decision decision = approvalGate.check(nodeName, context);
        if (!decision.requiresApproval()) {
            return null;
        }
        String reason = decision.reason() != null ? decision.reason()
                : "node '" + nodeName + "' requires human approval";
        log.info("graph.approval.required: graph={} node={} reason={}",
                name, nodeName, reason);
        ApprovalRequest request = new ApprovalRequest(nodeName, reason);
        InterruptRequest interrupt = new InterruptRequest(
                "approval.required:" + nodeName, request);
        return AgentResult.interrupted(interrupt);
    }

    /**
     * Resumes a run that was paused by an {@link ApprovalGate}, marking
     * {@code approvedNode} as approved so the gate's default factories
     * bypass it on the next attempt. Additional messages, if any, are
     * appended to the context before the run continues.
     *
     * <p>Custom {@link ApprovalGate} implementations that ignore
     * {@link ApprovalGate#APPROVED_KEY} must arrange their own bypass
     * signal — the marker is only honoured by the built-in factories
     * ({@link ApprovalGate#requireFor}, {@link ApprovalGate#when}).
     */
    public AgentResult resumeWithApproval(String runId, String approvedNode,
                                          org.springframework.ai.chat.messages.Message... additional) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(approvedNode, "approvedNode");
        CheckpointStore store = requireCheckpointStore();
        Checkpoint cp = store.load(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "No checkpoint found for runId=" + runId));
        AgentContext context = cp.context();
        java.util.Set<String> existing = context.get(ApprovalGate.APPROVED_KEY);
        java.util.Set<String> merged = new java.util.LinkedHashSet<>();
        if (existing != null) {
            merged.addAll(existing);
        }
        merged.add(approvedNode);
        context = context.with(ApprovalGate.APPROVED_KEY,
                java.util.Collections.unmodifiableSet(merged));
        if (additional != null && additional.length > 0) {
            context = context.withMessages(List.of(additional));
        }
        return run(context, cp.nextNode(), cp.iterations(), runId, null);
    }

    /**
     * Runs the configured {@link StatePolicy} against the state updates a
     * node returned. If any update is denied, the outcome is replaced with
     * a failed {@link AgentResult} carrying a {@link StatePolicyViolation},
     * so the existing {@link ErrorPolicy} (FAIL_FAST / RETRY_ONCE /
     * SKIP_NODE) decides what to do next.
     */
    private NodeOutcome enforceStatePolicy(String nodeName, NodeOutcome outcome) {
        if (statePolicy == StatePolicy.ALLOW_ALL || outcome.result.hasError()) {
            return outcome;
        }
        for (Map.Entry<io.github.datallmhub.agentflow4j.core.StateKey<?>, Object> entry
                : outcome.result.stateUpdates().entrySet()) {
            io.github.datallmhub.agentflow4j.core.StateKey<?> key = entry.getKey();
            StatePolicy.Decision decision = statePolicy.check(key, entry.getValue());
            if (decision.denied()) {
                String reason = decision.reason() != null ? decision.reason() : "denied";
                StatePolicyViolation violation =
                        new StatePolicyViolation(key, entry.getValue(), reason);
                log.warn("graph.state.deny: graph={} node={} key={} reason={}",
                        name, nodeName, key.name(), reason);
                return new NodeOutcome(
                        AgentResult.failed(AgentError.of(nodeName, violation)),
                        outcome.durationNanos);
            }
        }
        return outcome;
    }

    /**
     * Asks the {@link BudgetPolicy} whether the upcoming attempt fits in the
     * configured budget. Returns {@code null} when the call is allowed,
     * otherwise an interrupted {@link AgentResult} carrying an
     * {@link InterruptRequest} that the caller can resume from.
     */
    @Nullable
    private AgentResult gateBudget(String nodeName, AgentContext context) {
        if (budgetPolicy == BudgetPolicy.NOOP) {
            return null;
        }
        BudgetPolicy.Decision decision = budgetPolicy.check(nodeName, context);
        if (decision.allowed()) {
            return null;
        }
        BudgetPolicy.Breach breach = decision.breach();
        log.warn("graph.budget.breach: graph={} node={} scope={} limit={} projected={}",
                name, nodeName, breach.scope(), breach.limit(), breach.projected());
        InterruptRequest request = new InterruptRequest(
                "budget.exceeded:" + breach.scope(), breach);
        return AgentResult.interrupted(request);
    }

    private RetryPolicy effectivePolicy(Node node) {
        RetryPolicy override = node.retryPolicy();
        return override != null ? override : retryPolicy;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private AgentResult tryExecute(Node node, AgentContext context, int retryCount) {
        try {
            CircuitBreakerPolicy cb = node.circuitBreaker();
            AgentResult result = cb != null
                    ? cb.execute(node.name(), () -> node.execute(context))
                    : node.execute(context);
            AgentError err = result.error();
            if (err != null) {
                return AgentResult.failed(err.withRetryCount(retryCount));
            }
            return result;
        }
        catch (Throwable t) {
            return AgentResult.failed(new AgentError(node.name(), t, retryCount));
        }
    }

    /**
     * Resolves the next node to visit after {@code from}, applying edges in
     * declaration order with the following priority:
     *
     * <ol>
     *   <li>{@link Edge.OnResult} — tested first; wins if its predicate matches
     *       {@code (context, lastResult)}. Useful for routing based on node output
     *       (e.g. "needs-review", tool calls present, etc.).</li>
     *   <li>{@link Edge.Conditional} — tested next; wins if its predicate matches
     *       {@code context}. Useful for routing based on accumulated state.</li>
     *   <li>{@link Edge.Direct} — acts as a guaranteed fallback; the first direct
     *       edge found is used if no conditional edge fired. Only one direct fallback
     *       per source node is meaningful.</li>
     * </ol>
     *
     * <p><b>Important</b>: if you declare both an {@code OnResult} and a
     * {@code Direct} edge from the same node, the {@code OnResult} wins when its
     * predicate is {@code true}; the {@code Direct} is only taken when it is
     * {@code false}. This mirrors a classic "match / fallthrough" pattern.
     */
    private Optional<String> nextNode(String from, AgentContext context, AgentResult lastResult) {
        String directFallback = null;
        for (Edge edge : edges) {
            if (!edge.from().equals(from)) {
                continue;
            }
            if (edge instanceof Edge.OnResult onResult
                    && lastResult != null
                    && onResult.matches(context, lastResult)) {
                return Optional.of(onResult.to());
            }
            if (edge instanceof Edge.Conditional cond && cond.matches(context)) {
                return Optional.of(cond.to());
            }
            if (edge instanceof Edge.Direct direct && directFallback == null) {
                directFallback = direct.to();
            }
        }
        return Optional.ofNullable(directFallback);
    }

    private void notifyEnter(String node, AgentContext context) {
        for (AgentListener l : listeners) {
            try { l.onNodeEnter(name, node, context); }
            catch (Exception e) { log.warn("Listener failed on enter", e); }
        }
    }

    private void notifyExit(String node, AgentResult result, long duration) {
        for (AgentListener l : listeners) {
            try { l.onNodeExit(name, node, result, duration); }
            catch (Exception e) { log.warn("Listener failed on exit", e); }
        }
    }

    private void notifyError(String node, AgentError error) {
        log.error("graph.error: graph={} node={}", name, node, error.cause());
        for (AgentListener l : listeners) {
            try { l.onNodeError(name, node, error); }
            catch (Exception e) { log.warn("Listener failed on error", e); }
        }
    }

    private void notifyTransition(String from, String to) {
        log.info("graph.transition: graph={} from={} to={}", name, from, to);
        for (AgentListener l : listeners) {
            try { l.onTransition(name, from, to); }
            catch (Exception e) { log.warn("Listener failed on transition", e); }
        }
    }

    private void notifyGraphComplete(AgentResult result) {
        for (AgentListener l : listeners) {
            try { l.onGraphComplete(name, result); }
            catch (Exception e) { log.warn("Listener failed on graph complete", e); }
        }
    }

    private record NodeOutcome(AgentResult result, long durationNanos) {}

    public static final class Builder {
        private String name = "agent-graph";
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        private final List<Edge> edges = new ArrayList<>();
        private String entryNode;
        private ErrorPolicy errorPolicy = ErrorPolicy.FAIL_FAST;
        @Nullable private RetryPolicy retryPolicy;
        private BudgetPolicy budgetPolicy = BudgetPolicy.NOOP;
        private StatePolicy statePolicy = StatePolicy.ALLOW_ALL;
        private ApprovalGate approvalGate = ApprovalGate.NONE;
        private int maxIterations = 25;
        private final List<AgentListener> listeners = new ArrayList<>();
        @Nullable
        private CheckpointStore checkpointStore;
        @Nullable
        private RunLogStore runLogStore;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder addNode(String name, Agent agent) {
            return addNode(Node.of(name, agent));
        }

        public Builder addNode(String name, Agent agent, RetryPolicy nodeRetryPolicy) {
            return addNode(Node.of(name, agent,
                    Objects.requireNonNull(nodeRetryPolicy, "nodeRetryPolicy")));
        }

        public Builder addNode(String name, Agent agent,
                               @Nullable RetryPolicy nodeRetryPolicy,
                               @Nullable CircuitBreakerPolicy circuitBreaker) {
            return addNode(Node.of(name, agent, nodeRetryPolicy, circuitBreaker));
        }

        public Builder addNode(Node node) {
            Objects.requireNonNull(node, "node");
            if (nodes.putIfAbsent(node.name(), node) != null) {
                throw new IllegalStateException("Duplicate node: " + node.name());
            }
            if (entryNode == null) {
                entryNode = node.name();
            }
            return this;
        }

        public Builder entryNode(String name) {
            this.entryNode = Objects.requireNonNull(name, "entryNode");
            return this;
        }

        public Builder addEdge(String from, String to) {
            edges.add(Edge.direct(from, to));
            return this;
        }

        public Builder addEdge(Edge edge) {
            edges.add(Objects.requireNonNull(edge, "edge"));
            return this;
        }

        public Builder errorPolicy(ErrorPolicy policy) {
            this.errorPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder retryPolicy(RetryPolicy policy) {
            this.retryPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder budgetPolicy(BudgetPolicy policy) {
            this.budgetPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder statePolicy(StatePolicy policy) {
            this.statePolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder approvalGate(ApprovalGate gate) {
            this.approvalGate = Objects.requireNonNull(gate, "gate");
            return this;
        }

        public Builder runLog(RunLogStore store) {
            this.runLogStore = Objects.requireNonNull(store, "store");
            return this;
        }

        public Builder maxIterations(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxIterations must be > 0");
            }
            this.maxIterations = max;
            return this;
        }

        public Builder listener(AgentListener listener) {
            listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        public Builder checkpointStore(CheckpointStore store) {
            this.checkpointStore = Objects.requireNonNull(store, "store");
            return this;
        }

        public AgentGraph build() {
            return new AgentGraph(this);
        }
    }
}
