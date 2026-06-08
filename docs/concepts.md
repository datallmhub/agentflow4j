---
description: "AgentFlow4J core concepts: agents, teams, rules and execution — how the four building blocks map to the Java API."
---

# Core concepts

AgentFlow4J is built around four ideas. Understanding them is enough to read any recipe or tutorial in the docs.

---

## Agents

An **agent** is an autonomous unit that receives a context and returns a result. It can call an LLM, query a database, invoke a tool, or run deterministic logic — the framework doesn't care.

```java
// The simplest possible agent — a lambda
Agent greeter = ctx -> AgentResult.ofText("Hello, " + ctx.input());

// An LLM-backed agent
ExecutorAgent analyst = ExecutorAgent.builder()
    .chatClient(chatClient)
    .systemPrompt("Analyse this support request and classify it.")
    .build();
```

| Type | When to use |
|---|---|
| `ExecutorAgent` | Single LLM call with a system prompt |
| `ReActAgent` | Reasoning loop — thinks, acts, observes, repeats |
| `ParallelAgent` | Fan-out — runs multiple agents concurrently, aggregates results |

---

## Teams

A **team** is a group of agents that collaborate on a task. AgentFlow4J provides two ways to organise them.

**Squad API** — dynamic routing, minimal setup. A `CoordinatorAgent` receives the task and decides which `ExecutorAgent` handles it:

```java
CoordinatorAgent coordinator = CoordinatorAgent.builder()
    .executors(Map.of("billing", billingAgent, "technical", techAgent))
    .routingStrategy(RoutingStrategy.llmDriven(chatClient))
    .build();
```

**Graph API** — explicit flows, loops, conditions, full control. An `AgentGraph` defines nodes and edges:

```java
AgentGraph graph = AgentGraph.builder()
    .addNode("classify", classifyAgent)
    .addNode("reply",    replyAgent)
    .addEdge("classify", "reply")
    .build();
```

See [Two API levels](two-api-levels.md) for guidance on which to use.

---

## Rules

**Rules** define what agents are allowed to do. They are configured on the graph or on individual agents — not in agent code.

| Rule | What it controls |
|---|---|
| `ToolPolicy` | Which tools an agent may call (allow/deny list) |
| `StatePolicy` | Which state keys an agent may write |
| `BudgetPolicy` | Maximum spend per run, node, or call |
| `ApprovalGate` | Pauses execution — a human must approve before the node runs |

```java
AgentGraph graph = AgentGraph.builder()
    .addNode("payment", paymentAgent)
    .toolPolicy(ToolPolicy.allowList("crm.lookup").and(ToolPolicy.denyList("shell.execute")))
    .budgetPolicy(BudgetPolicy.perRun(2.00, estimator, meter))
    .approvalGate(ApprovalGate.requireFor("payment"))
    .build();
```

Agents are **not implicitly trusted**. Every gate is opt-in with a zero-overhead default.

See [Tool policy](tool-policy.md), [State policy](state-policy.md), [Budget policy](resilience.md#6-budget-policy-cost-gate), [Approval gate](approval-gate.md).

---

## Execution

**Execution** is how AgentFlow4J makes runs reliable. Three mechanisms work together:

**Retry** — failed nodes are retried with backoff. The `FailureClassifier` distinguishes transient errors from permanent failures and over-budget conditions, so retries don't burn money:

```java
RetryPolicy.exponential(3, Duration.ofSeconds(2))
    .withClassifier(FailureClassifier.defaults());
```

**Checkpoint** — graph state is persisted after every node. If the process restarts, the next run resumes from the last successful node — no work is lost:

```java
.checkpointStore(new JdbcCheckpointStore(dataSource))
```

**Observability** — every run produces a structured `RunLog` (node transitions, timing, tool calls) and Micrometer metrics (token count, cost, latency). Your existing Grafana dashboard sees agent executions alongside the rest of your application.

See [Resilience](resilience.md), [Run log](run-log.md), [Observability](observability.md).
