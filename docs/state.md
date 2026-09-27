# Typed state

Most agent frameworks store shared state as `Map<String, Object>` — fast to write, painful to debug. af4j uses `StateKey<T>` for compile-time typed access.

## Declaring keys

```java
StateKey<Double> CONFIDENCE = StateKey.of("confidence", Double.class);
StateKey<String> SUMMARY    = StateKey.of("summary",    String.class);
```

A `StateKey` carries a logical name *and* a Java type. The type is enforced when you read or write.

## Reading and writing

```java
// write
AgentContext ctx = context.with(CONFIDENCE, 0.85);

// read — no cast needed
double score = ctx.get(CONFIDENCE);
```

`AgentContext` is immutable: `with(...)` returns a new context. The graph runtime threads the new context through subsequent nodes, so state changes flow forward without shared mutable references.

## Mutating from a node

A node returns `AgentResult.stateUpdates(...)`; the graph merges those updates into the context atomically before the next transition.

```java
Agent classifier = ctx -> {
    double score = computeConfidence(ctx);
    return AgentResult.builder()
            .text("classified")
            .stateUpdates(Map.of(CONFIDENCE, score))
            .build();
};
```

This is the single-writer-per-step pattern: only the running node can produce updates, never a sibling. Concurrent agents in a `ParallelAgent` therefore see snapshots, not shared mutables — eliminating an entire class of multi-agent coordination bugs.
