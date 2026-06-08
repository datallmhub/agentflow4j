---
description: "Tutorial: LLM cost control for Java agents with AgentFlow4J BudgetPolicy. Set per-run, per-node, and per-call limits — stop runaway spend before it hits your bill."
---

# LLM cost control for Java agents — BudgetPolicy in practice

An LLM agent that loops, retries, or fans out to multiple nodes can burn through your API budget in minutes. A ReAct agent that gets stuck in a reasoning loop, a graph that retries on every transient error without a cost ceiling, or a batch job that processes 10× more documents than expected — all of these become expensive surprises on your billing dashboard.

This tutorial shows how to use `BudgetPolicy` in [AgentFlow4J](https://github.com/datallmhub/agentflow4j) to put hard limits on what every agent run can spend — per run, per node, or per individual LLM call.

---

## Why token counting isn't enough

The naive approach is to count tokens before calling the API:

```java
if (countTokens(prompt) > 4000) {
    throw new TooLongException();
}
```

This misses most of the real problems:

- A graph with 10 nodes each making 2 calls can be within per-call token limits but wildly over budget for the run
- Prices vary by model — 4000 tokens on GPT-4o costs 20× what the same prompt costs on Mistral
- Retry loops multiply cost — 3 retries on a 3-node graph can be 9× the baseline cost
- You catch the problem after you've paid, not before

`BudgetPolicy` operates on **estimated cost in currency**, not tokens. It enforces limits before calls are made, during execution — not on your bill at the end of the month.

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

## The three budget scopes

`BudgetPolicy` supports three scopes, which can be combined:

| Scope | What it limits | Factory method |
|---|---|---|
| `PER_RUN` | Total cost of all nodes in one `graph.invoke()` call | `BudgetPolicy.perRun(limit, ...)` |
| `PER_NODE` | Cost of a single node execution | `BudgetPolicy.perNode(limit, ...)` |
| `PER_CALL` | Cost of a single LLM call | `BudgetPolicy.perCall(limit, ...)` |
| Hierarchical | All three at once, nested | `BudgetPolicy.hierarchical(limits, ...)` |

### Per-run limit

Cap the total spend for a complete graph execution:

```java
AgentGraph graph = AgentGraph.builder()
    .addNode("research",  researchAgent)
    .addNode("draft",     draftAgent)
    .addNode("review",    reviewAgent)
    // abort the entire run if total spend exceeds $0.50
    .budgetPolicy(BudgetPolicy.perRun(0.50, estimator, meter))
    .build();
```

If `research` + `draft` together spend $0.48 and `review` is estimated to cost $0.05, the run aborts before `review` executes — you never see a bill for $0.53.

### Per-node limit

Prevent any single node from being a cost spike:

```java
.budgetPolicy(BudgetPolicy.perNode(0.10, estimator, meter))
```

Useful when one node uses a powerful (expensive) model while the rest use a cheap one.

### Per-call limit

The finest granularity — catch a single runaway prompt:

```java
.budgetPolicy(BudgetPolicy.perCall(0.02, estimator, meter))
```

### Hierarchical — all three at once

Most production configurations want all three:

```java
BudgetLimits limits = BudgetLimits.builder()
    .perRun(0.50)     // $0.50 max for the whole run
    .perNode(0.15)    // $0.15 max per node
    .perCall(0.05)    // $0.05 max per LLM call
    .build();

.budgetPolicy(BudgetPolicy.hierarchical(limits, estimator, meter))
```

---

## Wiring the estimator and meter

`BudgetPolicy` needs two collaborators:

- **`CostEstimator`** — estimates cost *before* a call, based on model and prompt length
- **`CostMeter`** — records actual cost *after* a call, based on the response's usage data

Both are Spring beans provided by the `agentflow4j-starter` auto-configuration when a provider starter is on the classpath. You can also define custom implementations:

```java
@Bean
CostEstimator mistralCostEstimator() {
    return CostEstimator.perToken(
        "mistral-small-latest",
        0.000_002,   // $0.000002 per input token
        0.000_006);  // $0.000006 per output token
}
```

---

## Handling a budget exception

When a limit is exceeded, AgentFlow4J throws `BudgetExceededException`. Handle it in your service layer:

```java
@Service
public class ContentService {

    private final AgentGraph graph;

    public ContentResult generate(String topic) {
        try {
            AgentResult result = graph.invoke(AgentContext.of(topic));
            return ContentResult.of(result.context().get(OUTPUT));
        } catch (BudgetExceededException e) {
            log.warn("Run aborted: budget exceeded — scope={}, limit={}, actual={}",
                e.scope(), e.limit(), e.actual());
            return ContentResult.budgetExceeded(e.actual());
        }
    }
}
```

The exception carries `scope()` (PER_RUN / PER_NODE / PER_CALL), `limit()` (your configured cap), and `actual()` (estimated spend at the point of abort).

---

## Cost-aware retry

The most dangerous combination is a retry loop without a budget limit. By default `RetryPolicy` retries on any error — including `BudgetExceededException`. Fix this by configuring the `FailureClassifier`:

```java
RetryPolicy retryPolicy = RetryPolicy.exponential(3, Duration.ofSeconds(2))
    .withClassifier(FailureClassifier.defaults());
```

`FailureClassifier.defaults()` marks `BudgetExceededException` as `OVER_BUDGET` — the policy stops retrying immediately instead of re-running an already expensive node three more times.

You can also define custom rules:

```java
FailureClassifier classifier = FailureClassifier.builder()
    .when(BudgetExceededException.class, FailureClass.OVER_BUDGET)
    .when(RateLimitException.class,      FailureClass.TRANSIENT)
    .when(InvalidPromptException.class,  FailureClass.PERMANENT)
    .build();
```

---

## Observing spend in real time

Every run emits Micrometer metrics that your existing dashboards can read:

| Metric | Description |
|---|---|
| `agentflow4j.run.cost` | Actual cost of a completed run |
| `agentflow4j.node.cost` | Cost of a single node execution |
| `agentflow4j.budget.exceeded` | Count of runs aborted by budget policy |

In Grafana, alert when `agentflow4j.budget.exceeded` exceeds your threshold — you'll know before the next billing period.

---

## Complete example

```java
@Configuration
public class CostControlledGraphConfig {

    @Bean
    AgentGraph researchGraph(
            Agent researcher,
            Agent drafter,
            CostEstimator estimator,
            CostMeter meter) {

        BudgetLimits limits = BudgetLimits.builder()
            .perRun(0.50)
            .perNode(0.15)
            .perCall(0.05)
            .build();

        RetryPolicy retry = RetryPolicy.exponential(3, Duration.ofSeconds(2))
            .withClassifier(FailureClassifier.defaults());

        return AgentGraph.builder()
            .name("research-workflow")
            .addNode("research", researcher)
            .addNode("draft",    drafter)
            .addEdge("research", "draft")
            .budgetPolicy(BudgetPolicy.hierarchical(limits, estimator, meter))
            .retryPolicy(retry)
            .build();
    }
}
```

```java
@Service
public class ResearchService {

    private final AgentGraph graph;

    public String research(String query) {
        try {
            AgentResult result = graph.invoke(AgentContext.of(query));
            return result.text();
        } catch (BudgetExceededException e) {
            return "Research aborted: budget cap reached (spent: $" + e.actual() + ")";
        }
    }
}
```

---

## Next steps

- **[Checkpoint and resume](../recipes/durable-runs.md)** — if a budget-exceeded run needs to be retried manually with a different model, checkpoint lets you pick up where it stopped
- **[Observability](../observability.md)** — Micrometer tags and dashboard queries for spend by agent, model, and run type
- **[Resilience & retry](../resilience.md)** — full `RetryPolicy` + `FailureClassifier` reference
