# Durable runs — crash mid-workflow, resume where you left off

The most common question about agent runtimes: *"what happens if a step fails in the middle?"*

AgentFlow4J checkpoints after **every node**. If a step fails, the process crashes, or you deliberately pause for a human, you `resume(runId)` and execution continues **from the node that didn't finish** — the steps that already succeeded do not re-run (and their LLM calls aren't paid for again).

## The store

State is persisted through a `CheckpointStore`. Two production backends ship in `agentflow4j-checkpoint` (JDBC and Redis); `InMemoryCheckpointStore` lives in the graph module for tests.

Typed state (`StateKey<T>`) is serialized with a Jackson codec. Register the keys you put in state so they survive the round-trip:

```java
StateKey<String> ROUTE = StateKey.of("route", String.class);

JacksonCheckpointCodec codec =
        new JacksonCheckpointCodec(new StateTypeRegistry().register(ROUTE));

JdbcCheckpointStore store =
        new JdbcCheckpointStore(jdbcTemplate, new DataSourceTransactionManager(dataSource), codec);
store.createTableIfMissing();
```

(For Redis: `new RedisCheckpointStore(redisOperations, codec)` — same `CheckpointStore` interface, nothing else changes.)

## Run with a runId

```java
AgentGraph graph = AgentGraph.builder()
        .addNode("plan", planAgent)
        .addNode("approve", approveAgent)     // interrupts for human sign-off
        .addNode("dispatch", dispatchAgent)
        .addEdge("plan", "approve")
        .addEdge("approve", "dispatch")
        .checkpointStore(store)
        .build();

AgentResult result = graph.invoke(AgentContext.of("ticket-42"), "run-42");
```

`plan` runs and writes `ROUTE=team-A` to state. `approve` returns `AgentResult.interrupted("need human approval")`. The graph persists a checkpoint whose `nextNode` is `approve`, with the state captured, and returns — `dispatch` never fires.

```java
result.isInterrupted();                          // true
store.load("run-42").orElseThrow().nextNode();   // "approve"
store.load("run-42").orElseThrow().context().get(ROUTE);  // "team-A" — survived serialization
```

## Resume — even after a full restart

The checkpoint is the only thing that needs to survive. You can rebuild the graph from scratch (new process, new agent instances) and resume off the store:

```java
// fresh JVM, brand-new AgentGraph + agents, same CheckpointStore
AgentResult done = graph.resume("run-42", new UserMessage("approved by alice"));

done.text();   // "dispatched to team-A"
```

What happens:
- `plan` does **not** re-run — it already completed before the checkpoint
- `approve` re-enters (it's the node that interrupted), now sees the human's message and proceeds
- `dispatch` runs with the state restored from the checkpoint (`ROUTE=team-A`)
- on success, the checkpoint is **deleted** automatically

## The three failure modes, handled

| Failure | Behaviour |
|---|---|
| **Transient** (network blip, rate limit) | `RetryPolicy` retries with backoff — `RetryPolicy.exponential(3, Duration.ofMillis(200))`, per-node override, predicate for which exceptions are worth retrying |
| **Crash mid-run** (JVM dies at step 3 of 5) | `resume(runId)` picks up at step 3; steps 1–2 don't re-run |
| **Permanent** (bad input, logic error) | `ErrorPolicy` decides: `FAIL_FAST` stops with the error surfaced, `SKIP_NODE` logs and continues |

Combine them: a flaky step retries, a crashed run resumes, an unrecoverable step stops cleanly — and the [run log](../run-log.md) shows exactly which node failed and why.

## See also

- [Resilience & error handling](../resilience.md) — RetryPolicy, ErrorPolicy, circuit breaker
- [Approval gate](../approval-gate.md) — the human-in-the-loop variant of "interrupt then resume"
- [Run log](../run-log.md) — the timeline that makes a failed run debuggable
