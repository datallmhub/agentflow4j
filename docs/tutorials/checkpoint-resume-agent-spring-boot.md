---
description: "Tutorial: checkpoint and resume AI agent workflows in Spring Boot with AgentFlow4J. Survive restarts, recover from failures, and resume paused runs — without re-running completed nodes."
---

# Checkpoint and resume AI agent workflows in Spring Boot

A multi-step agent workflow that takes minutes to run is fragile by default. A server restart, an out-of-memory error, or a deployment mid-run wipes everything — the next invocation starts from scratch, re-running every node, re-spending every token, re-calling every tool.

This tutorial shows how to add **durable execution** to your agent workflows with [AgentFlow4J](https://github.com/datallmhub/agentflow4j)'s checkpoint system: persist graph state after every node, survive any failure, and resume exactly where execution left off.

---

## How checkpointing works

After each node completes successfully, AgentFlow4J serialises the full `AgentContext` — all typed state keys and their values — to a `CheckpointStore`. The checkpoint includes:

- The run ID (stable across restarts)
- The name of the last completed node
- The full context at that point

When a run is resumed, AgentFlow4J loads the checkpoint, identifies the next node to execute, and continues from there. Completed nodes are skipped entirely — no repeated LLM calls, no duplicate tool invocations, no double-spending.

---

## Setup

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.datallmhub.agentflow4j</groupId>
    <artifactId>agentflow4j-starter</artifactId>
    <version>v0.7.0</version>
</dependency>
```

---

## Step 1 — Choose a checkpoint store

AgentFlow4J ships three implementations:

| Store | When to use |
|---|---|
| `InMemoryCheckpointStore` | Tests — state is lost on restart |
| `JdbcCheckpointStore` | Any Spring app with a datasource — uses your existing database |
| `RedisCheckpointStore` | High-throughput or multi-instance deployments |

For most Spring Boot applications, `JdbcCheckpointStore` is the right choice:

```java
@Bean
CheckpointStore checkpointStore(DataSource dataSource) {
    return new JdbcCheckpointStore(dataSource);
}
```

The store creates its table automatically on first use (`agentflow_checkpoint`). No Flyway migration needed.

---

## Step 2 — Add the store to your graph

```java
@Bean
AgentGraph researchGraph(
        Agent researcher,
        Agent drafter,
        Agent publisher,
        CheckpointStore checkpointStore) {

    return AgentGraph.builder()
        .name("content-pipeline")
        .addNode("research",  researcher)
        .addNode("draft",     drafter)
        .addNode("publish",   publisher)
        .addEdge("research",  "draft")
        .addEdge("draft",     "publish")
        // persist context after every node
        .checkpointStore(checkpointStore)
        .build();
}
```

That is the only change required. The rest of your code stays the same.

---

## Step 3 — Capture the run ID

`AgentResult` always carries a `runId()`. Persist it if you need to resume later:

```java
@Service
public class ContentPipeline {

    private final AgentGraph graph;

    public PipelineRun start(String topic) {
        AgentResult result = graph.invoke(AgentContext.of(topic));

        if (result.isInterrupted()) {
            // Paused by an ApprovalGate — save the run ID
            return PipelineRun.paused(result.runId());
        }

        return PipelineRun.completed(result.text());
    }

    public PipelineRun resume(String runId) {
        AgentResult result = graph.resume(runId);
        return PipelineRun.completed(result.text());
    }
}
```

If the process crashes mid-run, call `graph.resume(runId)` on the next start. The graph loads the last checkpoint and continues from the next unexecuted node.

---

## Automatic resume on startup

For batch or daemon workloads, resume all in-progress runs on startup:

```java
@Component
public class RunRecovery implements ApplicationRunner {

    private final AgentGraph graph;
    private final CheckpointStore checkpointStore;

    @Override
    public void run(ApplicationArguments args) {
        // Find all runs that were interrupted before completion
        checkpointStore.findIncomplete(graph.name())
            .forEach(runId -> {
                log.info("Resuming interrupted run: {}", runId);
                graph.resume(runId);
            });
    }
}
```

On restart after a crash, all in-flight runs continue automatically — no manual intervention, no lost work.

---

## What survives a restart

The checkpoint stores the full typed context. Every value written by a completed node is available when the resumed run continues:

```java
// Run 1: researcher completes, drafter crashes at 60%
// Checkpoint holds: RESEARCH_SUMMARY → "..."

// Run 2: graph.resume(runId)
// researcher is SKIPPED — already completed
// drafter starts from scratch (it had not completed)
// All context written by researcher is available to drafter
```

Nodes that were in progress when the crash happened re-run from the beginning of that node — AgentFlow4J checkpoints at node boundaries, not within a node. This is safe: a node's output mapper only runs after the LLM call succeeds, so a partial node write never reaches the checkpoint.

---

## Redis store for multi-instance deployments

If you run multiple instances (Kubernetes, Fargate, etc.), use `RedisCheckpointStore` so any instance can resume any run:

```java
@Bean
CheckpointStore checkpointStore(RedisConnectionFactory connectionFactory) {
    return new RedisCheckpointStore(connectionFactory);
}
```

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST}
      port: 6379
```

No other change required. The graph API is identical — the store is swapped at the bean level.

---

## Testing checkpoint behaviour

Use `InMemoryCheckpointStore` in tests — no database needed:

```java
@Test
void graph_resumes_after_first_node_completes() {
    AtomicInteger calls = new AtomicInteger();

    Agent slowNode = ctx -> {
        calls.incrementAndGet();
        return AgentResult.ofText("done");
    };

    AgentGraph graph = AgentGraph.builder()
        .addNode("step1", slowNode)
        .addNode("step2", ctx -> AgentResult.ofText("finished"))
        .addEdge("step1", "step2")
        .checkpointStore(new InMemoryCheckpointStore())
        .build();

    AgentResult first = graph.invoke(AgentContext.of("start"));
    String runId = first.runId();

    // Simulate a restart by creating a new graph instance with the same store
    AgentResult resumed = graph.resume(runId);
    assertThat(resumed.text()).isEqualTo("finished");

    // step1 ran once — the resume started from step2
    assertThat(calls.get()).isEqualTo(1);
}
```

---

## Complete picture

Checkpointing composes with the other AgentFlow4J governance primitives:

```java
AgentGraph graph = AgentGraph.builder()
    .name("payment-pipeline")
    .addNode("validate", validator)
    .addNode("transfer", transferAgent)
    .addNode("notify",   notifier)
    .addEdge("validate", "transfer")
    .addEdge("transfer", "notify")
    // Pause before transfer, wait for human sign-off
    .approvalGate(ApprovalGate.requireFor("transfer"))
    // Abort if total cost exceeds $0.20
    .budgetPolicy(BudgetPolicy.perRun(0.20, estimator, meter))
    // Retry transient failures, skip retrying over-budget runs
    .retryPolicy(RetryPolicy.exponential(3, Duration.ofSeconds(2))
        .withClassifier(FailureClassifier.defaults()))
    // Persist state after every node
    .checkpointStore(new JdbcCheckpointStore(dataSource))
    .build();
```

The checkpoint is written before the approval gate fires — if the process crashes while waiting for approval, the resume correctly lands at the gate again, not at the beginning of the pipeline.

---

## Next steps

- **[Human-in-the-loop approval](human-in-the-loop-java-ai-agents.md)** — combine checkpointing with `ApprovalGate` for async human review
- **[LLM cost control](llm-cost-control-java-budget-policy.md)** — add budget limits to make long-running resumable runs cost-safe
- **[Durable runs recipe](../recipes/durable-runs.md)** — a self-contained runnable example of checkpoint + resume with Spring Boot and PostgreSQL
