---
description: "Guard writes to specific StateKey<T> across an AgentFlow4J multi-agent workflow. Prevent an LLM from flipping sensitive flags like payment.confirmed on its own — denials surface through the graph's error policy."
---

# State Policy

A `StatePolicy` is an allow/deny gate evaluated **before** a node's state updates are applied to `AgentContext`. It is the second piece of the *governed execution* model (after `ToolPolicy`).

## When to use it

- **Lock down sensitive flags**: forbid any node from setting `payment.confirmed` until a dedicated approval node runs.
- **Per-flow write scope**: an "ingest" sub-graph may write `draft.*`, but never `billing.*`.
- **Argument-aware rules**: refuse to set `payment.amount` above a threshold without explicit approval.

`StatePolicy` is **write-only** by design. Reads via `AgentContext.get(StateKey<T>)` are not intercepted — most production governance needs are about preventing unauthorised mutations, not blocking reads.

## The SPI

```java
@FunctionalInterface
public interface StatePolicy {
    Decision check(StateKey<?> key, @Nullable Object value);
    // factories: allowWriteKeys(...), denyWriteKeys(...), when(...), ALLOW_ALL, DENY_ALL
    // composition: policy.and(other)
}
```

A denied write surfaces as a `StatePolicyViolation` wrapped in an `AgentError`. The configured `ErrorPolicy` on the graph then decides how to react:

| Error policy | Behaviour on state denial |
|---|---|
| `FAIL_FAST` (default) | Graph stops, returns the failed result with the violation as cause |
| `SKIP_NODE` | Node's mutations dropped, graph continues at the next edge |

## Attaching a policy to a graph

```java
StatePolicy policy = StatePolicy.allowWriteKeys("draft.response", "ticket.category")
        .and(StatePolicy.denyWriteKeys("payment.confirmed"));

AgentGraph graph = AgentGraph.builder()
        .addNode("triage",  triageAgent)
        .addNode("writer",  writerAgent)
        .addEdge("triage", "writer")
        .statePolicy(policy)
        .build();
```

If `writerAgent` returns `AgentResult.stateUpdates(Map.of(PAYMENT_CONFIRMED, true))`, the graph runtime intercepts the mutation, replaces the outcome with a failed `AgentResult` carrying the `StatePolicyViolation`, and applies the graph's `ErrorPolicy`.

## Argument-aware rules

```java
StatePolicy noLargePayments = StatePolicy.when(
        (key, value) -> !(key.name().equals("payment.amount")
                && value instanceof Number n
                && n.doubleValue() > 1000.0),
        "payment.amount above 1000 requires approval");
```

## Composition

```java
StatePolicy allowed   = StatePolicy.allowWriteKeys("draft.response", "payment.amount");
StatePolicy guarded   = StatePolicy.when(
        (key, value) -> !(value instanceof String s && s.toLowerCase().contains("password")),
        "draft must not contain a password");
StatePolicy combined  = allowed.and(guarded);
```

`.and(other)` short-circuits on the first denial.

## Default behaviour

The default policy on `AgentGraph.Builder` is `StatePolicy.ALLOW_ALL`. No interception happens — **zero overhead** when no policy is configured.

## Reading the violation

```java
AgentResult result = graph.invoke(ctx);
if (result.hasError() && result.error().cause() instanceof StatePolicyViolation v) {
    log.warn("Blocked write to {} = {} ({})", v.key().name(), v.value(), v.reason());
}
```

## Relationship to ToolPolicy

`ToolPolicy` and `StatePolicy` cover different layers:

| Concern | Policy |
|---|---|
| What tools an agent may call | [`ToolPolicy`](tool-policy.md) |
| What state keys an agent may write | `StatePolicy` |
| What total cost a run may incur | [`BudgetPolicy`](resilience.md#6-budget-policy-cost-gate) |

The three compose freely on the same graph.
