---
description: "Structured, replayable execution timeline per run for AgentFlow4J — node enter/exit timings, transitions, retries, and governance events (BUDGET_EXCEEDED, STATE_DENIED, APPROVAL_REQUIRED)."
---

# Run log — structured execution timeline

A run log is an ordered, timestamped record of everything that happened during a graph run: which nodes ran, how long they took, transitions, and — crucially — the governance events (`BudgetPolicy`, `StatePolicy`, `ApprovalGate`). It answers the question production teams actually have: **"what did my agent do, and why?"**

It is the data foundation for replay, debugging, and any timeline/observability UI.

## Enabling it

Attach a `RunLogStore` to the graph. The default `InMemoryRunLogStore` keeps events keyed by run id:

```java
InMemoryRunLogStore log = new InMemoryRunLogStore();

AgentGraph graph = AgentGraph.builder()
        .addNode("triage", triage)
        .addNode("refund", refund)
        .addEdge("triage", "refund")
        .runLog(log)
        .build();

graph.invoke(AgentContext.of("refund my order"), RunOptions.ofRunId("ticket-4521"));
```

Then read the timeline back by run id:

```java
for (AgentRunEvent e : graph.runLog("ticket-4521")) {
    System.out.println(e.describe());
}
```

```
#0 NODE_ENTER node=triage
#1 NODE_EXIT node=triage (412ms)
#2 TRANSITION node=triage — triage→refund
#3 NODE_ENTER node=refund
#4 NODE_EXIT node=refund (1203ms)
#5 GRAPH_COMPLETE — completed
```

## What gets recorded

| Event | When |
|---|---|
| `NODE_ENTER` | before a node executes |
| `NODE_EXIT` | after a node executes (carries `durationNanos`) |
| `NODE_ERROR` | a node failed (carries the message) |
| `TRANSITION` | the graph moved between nodes (detail `from→to`) |
| `APPROVAL_REQUIRED` | an `ApprovalGate` paused the run |
| `BUDGET_EXCEEDED` | a `BudgetPolicy` denied a call |
| `STATE_DENIED` | a `StatePolicy` blocked a write |
| `GRAPH_COMPLETE` | the run finished (completed, failed, interrupted) |

Each `AgentRunEvent` is a flat record (`runId`, `sequence`, `epochMillis`, `type`, `node`, `detail`, `durationNanos`) — trivially serializable to JSON, a log pipeline, or a UI.

## Run ids

The `runId` is a general run identifier. `graph.invoke(ctx, RunOptions.ofRunId(runId))` uses it for **both** the run log and checkpointing — and now requires **neither** store:

- with a `RunLogStore` → events are queryable via `graph.runLog(runId)`
- with a `CheckpointStore` → the run is checkpointed/resumable
- with both → correlated by the same id
- with neither → behaves like `invoke(ctx)` but with your chosen id

Plain `invoke(ctx)` still records events under an internally generated id; use the `runId` overload when you want to query the log afterward.

## Custom sinks

Implement `RunLogStore` to stream events somewhere durable:

```java
class JsonlRunLogStore implements RunLogStore {
    private final Path file;
    @Override public void append(AgentRunEvent e) {
        Files.writeString(file, toJson(e) + "\n", CREATE, APPEND);
    }
    // events(runId) / runIds() read them back
}
```

Implementations must be thread-safe — one graph instance can run concurrently on multiple threads, each with its own run id.

## Relationship to observability

The run log is intentionally a small, in-process primitive. It is the substrate for:

- a **replay/debugger** view (re-read a finished run step by step)
- a **live trace UI** (render the event stream as nodes light up)
- **OpenTelemetry export** (map each event to a span)

Those build on top of this; the log itself stays dependency-free.
