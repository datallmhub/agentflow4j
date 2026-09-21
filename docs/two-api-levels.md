---
description: "AgentFlow4J's two API levels: dynamic Squad routing for quick multi-agent setups, or explicit Graph orchestration for loops, branches and conditional edges in Spring AI."
---

# Two API levels

AgentFlow4J exposes two layers. Pick the one that matches how much control you need.

## Level 1 — Squad API (recommended for most apps)

Dynamic routing, minimal setup. A `CoordinatorAgent` routes to a set of `ExecutorAgent`s — you focus on the agents, not the plumbing.

```java
CoordinatorAgent coordinator = CoordinatorAgent.builder()
        .executors(Map.of(
            "research", researchExecutor,
            "analysis", analysisExecutor,
            "writing",  writingExecutor
        ))
        .routingStrategy(RoutingStrategy.llmDriven(chatClient))
        .build();

AgentResult result = coordinator.execute(AgentContext.of("..."));
```

Use this when:

- You have several specialist agents and want an LLM (or simple rules) to pick the right one per request.
- You do not need explicit loops or conditional fan-out.

## Level 2 — Graph API

Explicit flows, loops, conditions, full control over execution.

```java
AgentGraph graph = AgentGraph.builder()
        .addNode("research", researcher)
        .addNode("analyze",  analyzer)
        .addNode("write",    writer)
        .addEdge("research", "analyze")
        .addEdge(Edge.conditional("analyze",
                ctx -> ctx.get(CONFIDENCE).doubleValue() < 0.7,
                "research"))                               // loop back
        .addEdge("analyze", "write")                       // fallback: forward
        .retryPolicy(RetryPolicy.once())
        .build();

AgentResult result = graph.invoke(AgentContext.of("..."));
```

Use this when:

- You need conditional edges, loops, or fan-out / fan-in.
- You want per-node retry policies, circuit breakers, or budget gates.
- You need checkpoint-based recovery from a specific node.

The two levels compose: a `CoordinatorAgent` is itself an `Agent`, so it can be dropped into a graph as a node.
