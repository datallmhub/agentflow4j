package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentEvent;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final int maxConcurrency;
    private final Map<String, String> rejectionRoutes;
    private final Map<String, Set<String>> predecessors;
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
        this.retryPolicy = b.retryPolicy != null ? b.retryPolicy : RetryPolicy.none();
        this.budgetPolicy = b.budgetPolicy;
        this.statePolicy = b.statePolicy;
        this.approvalGate = b.approvalGate;
        this.maxIterations = b.maxIterations;
        this.maxConcurrency = b.maxConcurrency;
        this.rejectionRoutes = Map.copyOf(b.rejectionRoutes);
        Map<String, Set<String>> incoming = new LinkedHashMap<>();
        for (Edge edge : this.edges) {
            if (!edge.from().equals(edge.to())) {
                incoming.computeIfAbsent(edge.to(), k -> new java.util.LinkedHashSet<>()).add(edge.from());
            }
        }
        this.predecessors = Map.copyOf(incoming);
        this.listeners = List.copyOf(b.listeners);
        this.checkpointStore = b.checkpointStore;
        this.runLogStore = b.runLogStore;

        validate();
    }

    private void validate() {
        if (!nodes.containsKey(entryNode)) {
            throw new IllegalStateException("Entry node '" + entryNode + "' is not registered");
        }
        for (Map.Entry<String, String> route : rejectionRoutes.entrySet()) {
            if (!nodes.containsKey(route.getKey()) || !nodes.containsKey(route.getValue())) {
                throw new IllegalStateException("Rejection route between unknown nodes: "
                        + route.getKey() + " -> " + route.getValue());
            }
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
        return invoke(initial, RunOptions.defaults());
    }

    /**
     * Runs the graph with per-run settings. With a {@link RunOptions#runId() runId},
     * the run is checkpointed (if a {@link CheckpointStore} is configured) and its
     * {@link RunLogStore} entries are queryable via {@link #runLog(String)}; neither
     * store is required.
     */
    public AgentResult invoke(AgentContext initial, RunOptions options) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(options, "options");
        String runId = options.runId();
        if (runId != null && checkpointStore != null) {
            saveCheckpoint(checkpointStore, new Checkpoint(runId, entryNode, initial, 0, null));
        }
        return run(initial, List.of(entryNode), 0, runId, deadline(options.timeout()));
    }

    @Override
    public AgentResult execute(AgentContext context) {
        return invoke(context);
    }

    @Override
    public Flux<AgentEvent> executeStream(AgentContext context) {
        return invokeStream(context);
    }

    public AgentResult resume(String runId) {
        return resume(runId, ResumeOptions.none());
    }

    /**
     * Continues a checkpointed run from its next node. Nodes listed in
     * {@link ResumeOptions#approvedNodes()} are marked as approved so the
     * built-in {@link ApprovalGate} factories ({@link ApprovalGate#requireFor},
     * {@link ApprovalGate#when}) let them run. A custom gate that ignores
     * {@link ApprovalGate#APPROVED_KEY} must arrange its own bypass signal.
     */
    public AgentResult resume(String runId, ResumeOptions options) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(options, "options");
        Checkpoint cp = requireCheckpointStore().load(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "No checkpoint found for runId=" + runId));
        AgentContext context = cp.context();
        if (!options.approvedNodes().isEmpty()) {
            java.util.Set<String> existing = context.get(ApprovalGate.APPROVED_KEY);
            java.util.Set<String> merged = new java.util.LinkedHashSet<>();
            if (existing != null) {
                merged.addAll(existing);
            }
            merged.addAll(options.approvedNodes());
            context = context.with(ApprovalGate.APPROVED_KEY,
                    java.util.Collections.unmodifiableSet(merged));
        }
        if (!options.messages().isEmpty()) {
            context = context.withMessages(options.messages());
        }
        Set<String> completed = new java.util.LinkedHashSet<>(cp.completedNodes());
        completed.removeAll(options.invalidatedNodes());
        List<String> frontier = new ArrayList<>(cp.nextNodes());
        for (Map.Entry<String, String> rejection : options.rejectedNodes().entrySet()) {
            String rejected = rejection.getKey();
            frontier.remove(rejected);
            String target = rejectionRoutes.get(rejected);
            log.info("graph.approval.rejected: graph={} node={} reason={} route={}",
                    name, rejected, rejection.getValue(), target);
            if (target != null) {
                // The route may lead back through nodes that already ran: the memo keeps them from repeating.
                addUnique(frontier, target);
            }
        }
        if (frontier.isEmpty()) {
            requireCheckpointStore().delete(runId);
            String reason = "approval.rejected:" + String.join(",", options.rejectedNodes().keySet());
            return AgentResult.interrupted(new InterruptRequest(reason, options.rejectedNodes()));
        }
        return run(context, frontier, cp.iterations(), runId, null, completed);
    }

    @Nullable
    private static Long deadline(@Nullable Duration timeout) {
        return timeout != null ? System.nanoTime() + timeout.toNanos() : null;
    }

    private CheckpointStore requireCheckpointStore() {
        if (checkpointStore == null) {
            throw new IllegalStateException(
                    "AgentGraph has no CheckpointStore; configure one via Builder.checkpointStore(...)");
        }
        return checkpointStore;
    }

    /**
     * Drives the graph one wave at a time: every node of the current frontier
     * runs (in parallel when the frontier holds more than one node), their
     * state updates are merged, and the successors they select form the next
     * frontier. A node with several outgoing {@link Edge.Direct} edges fans
     * out; a node that several branches lead to runs once, after the wave that
     * produced it.
     */
    private AgentResult run(AgentContext context, List<String> startFrontier,
                            int startIterations, @Nullable String runId,
                            @Nullable Long deadlineNanos) {
        return run(context, startFrontier, startIterations, runId, deadlineNanos, Set.of());
    }

    private AgentResult run(AgentContext context, List<String> startFrontier,
                            int startIterations, @Nullable String runId,
                            @Nullable Long deadlineNanos, Set<String> alreadyCompleted) {
        log.info("graph.start: graph={}", name);
        List<String> frontier = List.copyOf(startFrontier);
        // Nodes a previous attempt of this run completed. Each entry is consumed
        // the first time it is skipped, so a loop can still revisit the node.
        Set<String> memo = new java.util.LinkedHashSet<>(alreadyCompleted);
        // What the checkpoint carries, so a later resume skips them in turn.
        Set<String> completed = new java.util.LinkedHashSet<>(alreadyCompleted);
        AgentResult lastResult = null;
        int iterations = startIterations;
        CheckpointStore store = checkpointStore;
        RunRecorder recorder = RunRecorder.forRun(
                runId != null ? runId : java.util.UUID.randomUUID().toString(), runLogStore);

        while (!frontier.isEmpty()) {
            String head = frontier.get(0);
            if (Thread.currentThread().isInterrupted()) {
                AgentError err = AgentError.of(head,
                        new InterruptedException("Graph execution interrupted"));
                recorder.error(head, "interrupted");
                notifyError(head, err);
                return AgentResult.failed(err);
            }
            if (deadlineNanos != null && System.nanoTime() > deadlineNanos) {
                AgentError err = AgentError.of(head,
                        new java.util.concurrent.TimeoutException(
                                "Graph exceeded timeout before entering node '" + head + "'"));
                recorder.error(head, "timeout");
                notifyError(head, err);
                return AgentResult.failed(err);
            }
            if (++iterations > maxIterations) {
                AgentError err = AgentError.of(head,
                        new IllegalStateException("Max iterations exceeded: " + maxIterations));
                recorder.error(head, "max iterations exceeded");
                notifyError(head, err);
                return AgentResult.failed(err);
            }

            // A join waits for every predecessor that is still on this frontier,
            // for instance a branch held back by an ApprovalGate.
            List<String> ready = new ArrayList<>();
            List<String> deferred = new ArrayList<>();
            for (String node : frontier) {
                if (waitsForFrontier(node, frontier)) {
                    deferred.add(node);
                }
                else {
                    ready.add(node);
                }
            }
            if (ready.isEmpty()) {
                // Every node depends on another one of the frontier (a cycle):
                // run them all rather than deadlock.
                ready = new ArrayList<>(frontier);
                deferred.clear();
            }

            List<String> toRun = new ArrayList<>();
            for (String node : ready) {
                if (memo.remove(node)) {
                    log.info("graph.node.skipped: graph={} node={} reason=already completed", name, node);
                    recorder.skipped(node);
                }
                else {
                    toRun.add(node);
                }
            }
            List<NodeAttempt> attempts = toRun.isEmpty() ? List.of() : runWave(toRun, context, recorder);

            // A node held back by an ApprovalGate never ran: it stays on the frontier.
            List<String> pending = new ArrayList<>();
            AgentResult pendingInterrupt = null;
            for (NodeAttempt attempt : attempts) {
                if (attempt.approvalRequired) {
                    pending.add(attempt.node);
                    if (pendingInterrupt == null) {
                        pendingInterrupt = attempt.result;
                    }
                }
            }

            List<NodeAttempt> ran = attempts.stream().filter(a -> !a.approvalRequired).toList();
            for (NodeAttempt attempt : ran) {
                if (attempt.result.hasError() && errorPolicy == ErrorPolicy.FAIL_FAST) {
                    recorder.complete("failed at " + attempt.node);
                    notifyGraphComplete(attempt.result);
                    return attempt.result;
                }
            }

            AgentContext merged;
            try {
                merged = mergeWave(context, ran);
            }
            catch (StateConflictException conflict) {
                AgentError err = AgentError.of(conflict.firstNode(), conflict);
                recorder.error(conflict.firstNode(), conflict.getMessage());
                notifyError(conflict.firstNode(), err);
                AgentResult failed = AgentResult.failed(err);
                recorder.complete("failed at " + conflict.firstNode());
                notifyGraphComplete(failed);
                return failed;
            }
            context = merged;
            for (NodeAttempt attempt : ran) {
                lastResult = attempt.result;
                if (!attempt.result.hasError() && !attempt.result.isInterrupted()) {
                    completed.add(attempt.node);
                }
            }

            // Successors of the nodes that ran; nodes held for approval or waiting
            // on a predecessor keep their place on the next frontier.
            List<String> next = new ArrayList<>(pending);
            for (String waiting : deferred) {
                addUnique(next, waiting);
            }
            for (String skipped : ready) {
                if (!toRun.contains(skipped) && !pending.contains(skipped)) {
                    for (String successor : nextNodes(skipped, context, lastResult)) {
                        addUnique(next, successor);
                    }
                }
            }
            for (NodeAttempt attempt : ran) {
                if (attempt.result.isInterrupted()) {
                    String reason = attempt.result.interrupt().reason();
                    if (reason.startsWith("budget.exceeded")) {
                        recorder.budgetExceeded(attempt.node, reason);
                        notifyBudgetExceeded(attempt.node, attempt.result.interrupt());
                    }
                    if (pendingInterrupt == null) {
                        pendingInterrupt = attempt.result;
                    }
                    addUnique(next, attempt.node);
                    continue;
                }
                for (String successor : nextNodes(attempt.node, context, attempt.result)) {
                    addUnique(next, successor);
                }
            }

            if (pendingInterrupt != null) {
                if (runId != null && store != null && !next.isEmpty()) {
                    saveCheckpoint(store, new Checkpoint(runId, next, context,
                            iterations - 1, pendingInterrupt.interrupt(), completed));
                }
                String reason = pendingInterrupt.interrupt().reason();
                recorder.complete("interrupted at " + next.get(0) + ": " + reason);
                notifyGraphComplete(pendingInterrupt);
                return pendingInterrupt;
            }

            if (runId != null && store != null) {
                if (!next.isEmpty()) {
                    saveCheckpoint(store, new Checkpoint(runId, next, context, iterations, null, completed));
                }
                else {
                    store.delete(runId);
                }
            }

            for (NodeAttempt attempt : ran) {
                for (String successor : nextNodes(attempt.node, context, attempt.result)) {
                    recorder.transition(attempt.node, successor);
                    notifyTransition(attempt.node, successor);
                }
            }
            frontier = next;
        }

        AgentResult finalResult = lastResult != null ? lastResult : AgentResult.ofText(null);
        recorder.complete("completed");
        notifyGraphComplete(finalResult);
        return finalResult;
    }

    /** Runs one wave: inline when it holds a single node, on a bounded pool otherwise. */
    private List<NodeAttempt> runWave(List<String> frontier, AgentContext context, RunRecorder recorder) {
        if (frontier.size() == 1) {
            return List.of(attemptNode(frontier.get(0), context, recorder));
        }
        int threads = Math.min(maxConcurrency, frontier.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            List<java.util.concurrent.Future<NodeAttempt>> futures = new ArrayList<>(frontier.size());
            for (String node : frontier) {
                futures.add(pool.submit(() -> attemptNode(node, context, recorder)));
            }
            List<NodeAttempt> attempts = new ArrayList<>(frontier.size());
            for (int i = 0; i < futures.size(); i++) {
                try {
                    attempts.add(futures.get(i).get());
                }
                catch (java.util.concurrent.ExecutionException ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    attempts.add(new NodeAttempt(frontier.get(i),
                            AgentResult.failed(AgentError.of(frontier.get(i), cause)), false));
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    attempts.add(new NodeAttempt(frontier.get(i),
                            AgentResult.failed(AgentError.of(frontier.get(i), ex)), false));
                }
            }
            return attempts;
        }
        finally {
            pool.shutdownNow();
        }
    }

    /** Runs a single node: approval gate, retries, budget, state policy, listeners. */
    private NodeAttempt attemptNode(String nodeName, AgentContext context, RunRecorder recorder) {
        Node node = nodes.get(nodeName);
        recorder.enter(nodeName);
        notifyEnter(nodeName, context);

        AgentResult approvalInterrupt = gateApproval(nodeName, context);
        if (approvalInterrupt != null) {
            recorder.approvalRequired(nodeName, approvalInterrupt.interrupt().reason());
            notifyApprovalRequired((ApprovalRequest) approvalInterrupt.interrupt().payload());
            notifyExit(nodeName, approvalInterrupt, 0L);
            return new NodeAttempt(nodeName, approvalInterrupt, true);
        }

        NodeOutcome outcome = enforceStatePolicy(nodeName, executeWithPolicy(node, context));
        recorder.exit(nodeName, outcome.durationNanos);
        notifyToolCalls(nodeName, outcome.result);
        notifyExit(nodeName, outcome.result, outcome.durationNanos);

        if (outcome.result.hasError()) {
            AgentError err = outcome.result.error();
            if (err != null && err.cause() instanceof StatePolicyViolation spv) {
                recorder.stateDenied(nodeName, spv.reason());
            }
            else {
                recorder.error(nodeName, errorMessage(err));
            }
            notifyError(nodeName, err);
        }
        return new NodeAttempt(nodeName, outcome.result, false);
    }

    /**
     * Applies the results of a wave to the fork context. Two branches writing
     * different values to the same key is a {@link StateConflictException}:
     * the run fails rather than depending on which branch finished first.
     */
    private AgentContext mergeWave(AgentContext forked, List<NodeAttempt> attempts) {
        if (attempts.size() > 1) {
            Map<io.github.datallmhub.agentflow4j.core.StateKey<?>, String> writers = new LinkedHashMap<>();
            Map<io.github.datallmhub.agentflow4j.core.StateKey<?>, Object> values = new LinkedHashMap<>();
            for (NodeAttempt attempt : attempts) {
                for (Map.Entry<io.github.datallmhub.agentflow4j.core.StateKey<?>, Object> entry
                        : attempt.result.stateUpdates().entrySet()) {
                    String previous = writers.putIfAbsent(entry.getKey(), attempt.node);
                    Object seen = values.putIfAbsent(entry.getKey(), entry.getValue());
                    if (previous != null && !Objects.equals(seen, entry.getValue())) {
                        throw new StateConflictException(entry.getKey(), previous, attempt.node);
                    }
                }
            }
        }
        AgentContext merged = forked;
        for (NodeAttempt attempt : attempts) {
            if (!attempt.result.hasError()) {
                merged = merged.applyResult(attempt.result);
            }
        }
        return merged;
    }

    /** True when another node of {@code frontier} leads into {@code node}. */
    private boolean waitsForFrontier(String node, List<String> frontier) {
        Set<String> incoming = predecessors.get(node);
        if (incoming == null) {
            return false;
        }
        for (String other : frontier) {
            if (!other.equals(node) && incoming.contains(other)) {
                return true;
            }
        }
        return false;
    }

    private static void addUnique(List<String> target, String node) {
        if (!target.contains(node)) {
            target.add(node);
        }
    }

    private record NodeAttempt(String node, AgentResult result, boolean approvalRequired) {}

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
        return invokeStream(initial, RunOptions.defaults());
    }

    /**
     * Streaming counterpart of {@link #invoke(AgentContext, RunOptions)}: the
     * same governance applies, and a run started with a
     * {@link RunOptions#runId() runId} can be continued via
     * {@link #resume(String, ResumeOptions)} after an interrupt.
     */
    public Flux<AgentEvent> invokeStream(AgentContext initial, RunOptions options) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(options, "options");
        return stream(initial, options);
    }

    private Flux<AgentEvent> stream(AgentContext initial, RunOptions options) {
        // Flux.create runs the imperative loop (including toIterable() inside tryStream)
        // on the subscriber's thread. subscribeOn(boundedElastic) ensures that thread
        // is always blocking-capable, even when the caller is a Netty/WebFlux event loop.
        return Flux.<AgentEvent>create(sink -> {
            String runId = options.runId();
            Long deadlineNanos = deadline(options.timeout());
            CheckpointStore store = checkpointStore;
            RunRecorder recorder = RunRecorder.forRun(
                    runId != null ? runId : java.util.UUID.randomUUID().toString(), runLogStore);
            try {
                log.info("graph.start: graph={}", name);
                AgentContext context = initial;
                String currentNode = entryNode;
                // Streaming visits branches one after another: a single ordered
                // event stream cannot interleave concurrent nodes.
                List<String> queued = new ArrayList<>();
                AgentResult lastResult = null;
                String previousNode = null;
                int iterations = 0;
                if (runId != null && store != null) {
                    saveCheckpoint(store, new Checkpoint(runId, entryNode, initial, 0, null));
                }

                while (currentNode != null) {
                    if (deadlineNanos != null && System.nanoTime() > deadlineNanos) {
                        AgentError err = AgentError.of(currentNode,
                                new java.util.concurrent.TimeoutException(
                                        "Graph exceeded timeout before entering node '" + currentNode + "'"));
                        recorder.error(currentNode, "timeout");
                        notifyError(currentNode, err);
                        sink.next(AgentEvent.completed(AgentResult.failed(err)));
                        sink.complete();
                        return;
                    }
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

                    AgentResult approvalInterrupt = gateApproval(currentNode, context);
                    if (approvalInterrupt != null) {
                        recorder.approvalRequired(currentNode, approvalInterrupt.interrupt().reason());
                        notifyApprovalRequired((ApprovalRequest) approvalInterrupt.interrupt().payload());
                        if (runId != null && store != null) {
                            saveCheckpoint(store, new Checkpoint(runId, currentNode, context,
                                    iterations - 1, approvalInterrupt.interrupt()));
                        }
                        notifyExit(currentNode, approvalInterrupt, 0L);
                        recorder.complete("approval required at " + currentNode);
                        notifyGraphComplete(approvalInterrupt);
                        sink.next(AgentEvent.completed(approvalInterrupt));
                        sink.complete();
                        return;
                    }

                    NodeOutcome outcome = streamNodeWithPolicy(node, context, sink);
                    outcome = enforceStatePolicy(currentNode, outcome);
                    recorder.exit(currentNode, outcome.durationNanos);
                    notifyToolCalls(currentNode, outcome.result);
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

                    if (outcome.result.isInterrupted()) {
                        String reason = outcome.result.interrupt().reason();
                        if (reason.startsWith("budget.exceeded")) {
                            recorder.budgetExceeded(currentNode, reason);
                            notifyBudgetExceeded(currentNode, outcome.result.interrupt());
                        }
                        if (runId != null && store != null) {
                            saveCheckpoint(store, new Checkpoint(runId, currentNode, context,
                                    iterations - 1, outcome.result.interrupt()));
                        }
                        recorder.complete("interrupted at " + currentNode + ": " + reason);
                        notifyGraphComplete(outcome.result);
                        sink.next(AgentEvent.completed(outcome.result));
                        sink.complete();
                        return;
                    }

                    List<String> successors = nextNodes(currentNode, context, lastResult);
                    for (String successor : successors) {
                        if (!queued.contains(successor)) {
                            queued.add(successor);
                        }
                    }
                    String next = queued.isEmpty() ? null : queued.remove(0);
                    if (runId != null && store != null) {
                        if (next != null) {
                            saveCheckpoint(store, new Checkpoint(runId, next, context, iterations, null));
                        }
                        else {
                            store.delete(runId);
                        }
                    }

                    previousNode = currentNode;
                    currentNode = next;
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
     * {@link #resume(String, ResumeOptions)}.
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
     * Runs the configured {@link StatePolicy} against the state updates a
     * node returned. If any update is denied, the outcome is replaced with
     * a failed {@link AgentResult} carrying a {@link StatePolicyViolation},
     * so the existing {@link ErrorPolicy} (FAIL_FAST /
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
     * Resolves the successors of {@code from}, applying edges in declaration
     * order with the following priority:
     *
     * <ol>
     *   <li>{@link Edge.OnResult}: tested first, and the first match wins alone.
     *       Useful for routing on a node's output.</li>
     *   <li>{@link Edge.Conditional}: tested next, and the first match wins
     *       alone. Useful for routing on accumulated state.</li>
     *   <li>{@link Edge.Direct}: the fallback when no predicate fired.
     *       <b>Every</b> direct edge is taken, so several direct edges out of
     *       one node are independent branches that run in parallel and join on
     *       the nodes they lead to.</li>
     * </ol>
     *
     * <p>If you declare both an {@code OnResult} and a {@code Direct} edge from
     * the same node, the {@code OnResult} wins when its predicate is
     * {@code true}; the direct edges are only taken when it is {@code false}.
     */
    private List<String> nextNodes(String from, AgentContext context, AgentResult lastResult) {
        List<String> directs = new ArrayList<>();
        for (Edge edge : edges) {
            if (!edge.from().equals(from)) {
                continue;
            }
            if (edge instanceof Edge.OnResult onResult
                    && lastResult != null
                    && onResult.matches(context, lastResult)) {
                return List.of(onResult.to());
            }
            if (edge instanceof Edge.Conditional cond && cond.matches(context)) {
                return List.of(cond.to());
            }
            if (edge instanceof Edge.Direct direct) {
                addUnique(directs, direct.to());
            }
        }
        return List.copyOf(directs);
    }

    private void saveCheckpoint(CheckpointStore store, Checkpoint checkpoint) {
        store.save(checkpoint);
        for (AgentListener l : listeners) {
            try { l.onCheckpoint(name, checkpoint); }
            catch (Exception e) { log.warn("Listener failed on checkpoint", e); }
        }
    }

    private void notifyToolCalls(String node, AgentResult result) {
        for (io.github.datallmhub.agentflow4j.core.ToolCallRecord call : result.toolCalls()) {
            for (AgentListener l : listeners) {
                try { l.onToolCall(name, node, call); }
                catch (Exception e) { log.warn("Listener failed on tool call", e); }
            }
        }
    }

    private void notifyApprovalRequired(ApprovalRequest request) {
        for (AgentListener l : listeners) {
            try { l.onApprovalRequired(name, request); }
            catch (Exception e) { log.warn("Listener failed on approval required", e); }
        }
    }

    private void notifyBudgetExceeded(String node, InterruptRequest interrupt) {
        for (AgentListener l : listeners) {
            try { l.onBudgetExceeded(name, node, interrupt); }
            catch (Exception e) { log.warn("Listener failed on budget exceeded", e); }
        }
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
        private int maxConcurrency = 4;
        private final Map<String, String> rejectionRoutes = new LinkedHashMap<>();
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

        /**
         * Where the run continues when {@code gatedNode} is rejected through
         * {@link ResumeOptions#ofRejection(String, String)}. Without a route,
         * a rejection ends the run.
         */
        public Builder onRejection(String gatedNode, String targetNode) {
            rejectionRoutes.put(Objects.requireNonNull(gatedNode, "gatedNode"),
                    Objects.requireNonNull(targetNode, "targetNode"));
            return this;
        }

        /** Upper bound on the nodes of one frontier running at the same time. */
        public Builder maxConcurrency(int max) {
            if (max <= 0) {
                throw new IllegalArgumentException("maxConcurrency must be > 0");
            }
            this.maxConcurrency = max;
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
