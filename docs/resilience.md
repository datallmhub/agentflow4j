---
description: "Production error handling for Java multi-agent workflows in AgentFlow4J: retry with backoff, circuit breakers per node, budget policy capping cost per run/node/call, and human-in-the-loop checkpoints."
---

# Resilience & Error Handling

LLM-based workflows are inherently non-deterministic. Network failures, rate limits, and "hallucinations" are part of the game. `agentflow4j` provides built-in tools to handle these gracefully.

---

## 1. Automatic Retries

Retries are configured on the **graph**, either as a default for every node or
per-node when one step needs a different policy. `RetryPolicy.exponential` gives
you capped exponential backoff with jitter.

```java
import java.time.Duration;

AgentGraph graph = AgentGraph.builder()
    // default for every node: 3 attempts, 1s base delay, x2 backoff
    .retryPolicy(RetryPolicy.exponential(3, Duration.ofSeconds(1)))
    .addNode("research", researchAgent)
    // per-node override: this flaky step gets 5 attempts
    .addNode("write", writerAgent, RetryPolicy.exponential(5, Duration.ofMillis(500)))
    .addEdge("research", "write")
    .build();
```

The built-in factories are `RetryPolicy.none()` (no retry), `RetryPolicy.once()`
(one extra attempt, no delay), and `RetryPolicy.exponential(maxAttempts, baseDelay)`.
The check runs **before every attempt, including retries**.

### Reason-aware retries

Blindly retrying every failure wastes time and money: a `400 Bad Request` will
fail identically on the next attempt, while a `429 Too Many Requests` often tells
you *exactly* how long to wait via its `Retry-After` header. A `RetryPolicy`
carries a `FailureClassifier` that sorts each failure into one of three
categories:

| `FailureCategory` | What the graph does                                                       |
| ----------------- | ------------------------------------------------------------------------- |
| `TRANSIENT`       | Retry. If the failure carries a `Retry-After` hint, that delay is honoured **instead of** the computed backoff. |
| `PERMANENT`       | Stop immediately — no further attempts, the error is returned as-is.      |
| `OVER_BUDGET`     | Stop and surface an `InterruptRequest` (see [Budget Policy](#6-budget-policy-cost-gate)) so a human can approve more budget and resume. |

The **default** classifier (`FailureClassifier.defaults()`, installed on every
policy unless you replace it) already recognises the common cases:

- `IOException` / `TimeoutException` → `TRANSIENT`
- Spring AI / Spring Web `5xx` and `429` → `TRANSIENT` (a `429`'s `Retry-After` is parsed and honoured)
- other `4xx` → `PERMANENT`
- `BudgetExceededException` → `OVER_BUDGET`

Spring exceptions are detected by class name, so `agentflow4j-graph` keeps **zero
compile-time Spring dependency**.

#### Adding your own rules

Classifiers compose: your classifier handles the failures it knows about and
returns `null` for everything else, delegating to the default via `orElse`.

```java
FailureClassifier domainRules = cause -> {
    if (cause instanceof QuotaExhaustedException) {
        return FailureClassification.overBudget("monthly quota hit");
    }
    if (cause instanceof InvalidPromptException) {
        return FailureClassification.permanent("prompt rejected by guardrail");
    }
    return null; // unknown — let the default classifier decide
};

RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofSeconds(1))
        .withClassifier(domainRules.orElse(FailureClassifier.defaults()));
```

`FailureClassification` exposes factories for each case —
`transientFailure()`, `transientFailure(Duration retryAfter)`, `permanent()`,
`permanent(String reason)`, and `overBudget(String reason)`. The optional
`reason` is recorded in logs and the audit trail.

> **Backward compatible:** the legacy `retryOn` predicate still works. When the
> classifier returns `null`, the policy falls back to it — `true` → `TRANSIENT`,
> `false` → `PERMANENT` — so existing policies keep their exact behaviour.

## 2. Structured Error Results

If an agent fails, it returns an `AgentResult` with error metadata. The framework doesn't just throw an exception; it allows you to inspect the failure.

```java
AgentResult result = agent.execute(context);
if (result.hasError()) {
    log.error("Agent failed: {}", result.error().message());
}
```

## 3. Circuit Breaker Support

When using `agentflow4j-starter`, we integrate with Spring AI's native retry and circuit breaker advisors. This ensures that if an LLM provider is down, your application remains stable and doesn't waste tokens on guaranteed failures.

## 4. Loop Prevention

Graphs can accidentally create infinite loops. We provide a **max-steps** guardrail to ensure that a workflow eventually terminates.

```java
AgentGraph graph = AgentGraph.builder()
    .maxSteps(10) // Fails if more than 10 transitions occur
    .build();
```

---

## 5. Human-in-the-loop

Sometimes, "resilience" means stopping and asking a human. By using **Checkpoints**, you can halt a workflow on a specific condition, notify a user via email/Slack, and resume once the human has provided the missing data.

---

## 6. Budget Policy (cost gate)

`RetryPolicy` counts attempts and elapsed time, but it is blind to **cost**. A single agent retrying a paid API overnight can quietly burn dollars. `BudgetPolicy` is a pluggable SPI that caps a run by cost — in whatever currency you choose (dollars, tokens, call counts).

### Three hierarchical tiers

A `BudgetLimits` has three caps, enforced in this order on every call:

| Scope  | Meaning                                                      |
| ------ | ------------------------------------------------------------ |
| `CALL` | Max cost of a **single** attempt — a $5 call against a $2 budget fails fast. |
| `NODE` | Max cumulative cost of one **node** over the entire run.     |
| `RUN`  | Max cumulative cost across **all nodes** in the run.         |

Any tier set to `Double.POSITIVE_INFINITY` is disabled. `BudgetLimits.run(double)` is shorthand for "only cap the run".

### Wiring a per-provider cost estimator

You supply two callbacks:

- **`CostEstimator`** — called **before** every attempt with `(nodeName, context)`. Return the worst-case cost of the upcoming call.
- **`CostMeter`** — called **after** a successful attempt with `(nodeName, result)`. Return the actual cost incurred, often derived from `AgentResult.usage()`.

Example: a dollar-denominated budget for an OpenAI-style provider charging $0.002 / 1K total tokens.

```java
double dollarsPerToken = 0.002 / 1000.0;

CostEstimator estimator = (node, ctx) -> {
    int promptTokens = ctx.messages().stream()
            .mapToInt(m -> m.getText().length() / 4)    // rough heuristic
            .sum();
    int worstCaseCompletion = 1024;
    return (promptTokens + worstCaseCompletion) * dollarsPerToken;
};

CostMeter meter = CostMeter.totalTokens().scaledBy(dollarsPerToken);

BudgetPolicy budget = BudgetPolicy.hierarchical(
        BudgetLimits.builder()
                .perRun(2.00)    // $2.00 hard cap per run
                .perNode(0.50)   // no single node may burn more than $0.50
                .perCall(0.10)   // refuse any single call > $0.10
                .build(),
        estimator,
        meter);

AgentGraph graph = AgentGraph.builder()
        .addNode("research", researchAgent)
        .addNode("write", writerAgent)
        .addEdge("research", "write")
        .budgetPolicy(budget)
        .build();
```

### How a breach surfaces

When `check` denies a call, the graph short-circuits the node and returns an `AgentResult` whose `interrupt()` is set:

```java
AgentResult result = graph.invoke(ctx);
if (result.isInterrupted() && result.interrupt().reason().startsWith("budget.exceeded:")) {
    BudgetPolicy.Breach breach = (BudgetPolicy.Breach) result.interrupt().payload();
    log.warn("Halted at scope={} (limit={}, projected={})",
            breach.scope(), breach.limit(), breach.projected());
    // Resume from a Checkpoint after a human approves more budget...
}
```

Because the breach uses the existing `InterruptRequest` mechanism it plugs into the human-in-the-loop / checkpoint flow described above — pause, notify, raise the limit, resume.

### Drop-in cost units

Need something simpler than dollars?

- **Call count**: `CostEstimator.perCall()` + `CostMeter.perCall()` — limits become "max 20 calls per run".
- **Tokens**: `CostMeter.totalTokens()` — read directly from `AgentResult.usage()`; limits are expressed in tokens.

### Gotchas

- The default `BudgetPolicy` is `NOOP`. You only get cost protection after calling `.budgetPolicy(...)` on the builder.
- The policy gates **before every attempt, including retries** — a flaky node will not silently chew through your run budget.
- Counters live on the `BudgetPolicy` instance. Use a fresh instance per run (or per tenant) if you do not want spending to carry over.

### Cost-aware routing (budget-aware router)

A budget can do more than halt a run — it can *degrade gracefully*. The
**budget-aware router** (in `agentflow4j-squad`) routes to a **premium** agent
while there is budget left, then switches to a cheaper **fallback** agent once
the remaining budget at a chosen scope drops below a threshold. So instead of
stopping dead at the limit, the squad keeps answering on a budget model.

This is the one cost-aware routing lever that is both **deterministic** and
**provably cheaper**: classifying request complexity ex-ante with an LLM would
itself cost a call (chicken-and-egg), whereas reading the live `BudgetPolicy`
counters is free.

```java
import io.github.datallmhub.agentflow4j.squad.RoutingStrategy;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;

BudgetPolicy budget = BudgetPolicy.hierarchical(
        BudgetLimits.builder().perRun(5.00).build(),
        estimator, meter);

// Use "premium" while >= $1.00 remains in the run budget, then "fallback".
RoutingStrategy router = RoutingStrategy.budgetAware(
        budget, BudgetPolicy.Scope.RUN, 1.00, "premium", "fallback");

CoordinatorAgent coordinator = CoordinatorAgent.builder()
        .executor("premium", premiumAgent)
        .executor("fallback", fallbackAgent)
        .routingStrategy(router)
        .build();
```

The router and the graph **must share the same `BudgetPolicy` instance** so the
router reads live spend. The decision is read from
`BudgetPolicy.remaining(scope, nodeName)`: while `remaining >= threshold` it
picks `premium`; once `remaining < threshold` (strictly less) it picks
`fallback`. Both executors must be registered or `selectExecutor` throws.

`remaining(...)` returns `Double.POSITIVE_INFINITY` for any unbounded scope (and
for the `NOOP` policy), so a budget-aware router wired to an uncapped scope
always stays on `premium` — fail-open, never silently cheap.
