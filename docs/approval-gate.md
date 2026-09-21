---
description: "Human-in-the-loop pause/resume on flagged nodes for Java/Spring multi-agent workflows. Async via Slack/webhook, built on checkpoints — the workflow survives a restart while it waits."
---

# Approval Gate

An `ApprovalGate` is a human-in-the-loop checkpoint evaluated **before** a node runs. It is the third piece of the *governed execution* model, alongside [`ToolPolicy`](tool-policy.md) and [`StatePolicy`](state-policy.md).

## When to use it

- **Sensitive actions**: pause before `payment.transfer`, `refund.process`, `user.delete`.
- **Threshold-based pauses**: ask a human when an amount exceeds a limit.
- **Pre-flight review**: pause the first time the graph hits a node so an operator can sanity-check the conversation so far.

The gate reuses the existing `InterruptRequest` + checkpoint mechanism: no new persistence layer, no new resume protocol.

## The SPI

```java
@FunctionalInterface
public interface ApprovalGate {
    Decision check(String nodeName, AgentContext context);
    // factories: requireFor(...), when(...), NONE
    // composition: gate.and(other)
}
```

A gate that returns `REQUIRE_APPROVAL` causes the graph to:

1. Build an `ApprovalRequest(nodeName, reason)` payload.
2. Emit an `InterruptRequest` with reason `approval.required:<node>`.
3. Save a checkpoint via the configured `CheckpointStore`.
4. Return an interrupted `AgentResult` to the caller.

## Pause / approve / resume

```java
InMemoryCheckpointStore store = new InMemoryCheckpointStore();

AgentGraph graph = AgentGraph.builder()
        .addNode("payment.transfer", transferAgent)
        .approvalGate(ApprovalGate.requireFor("payment.transfer"))
        .checkpointStore(store)
        .build();

// First call — pauses on the guarded node
AgentResult paused = graph.invoke(ctx, RunOptions.ofRunId("run-42"));
if (paused.isInterrupted()) {
    ApprovalRequest req = (ApprovalRequest) paused.interrupt().payload();
    notifySlack("Approve transfer? node=" + req.nodeName() + " reason=" + req.reason());
}

// ... later, when the human clicks Approve ...
AgentResult resumed = graph.resume("run-42", ResumeOptions.ofApproval("payment.transfer"));
```

`resume(runId, ResumeOptions.ofApproval(node))`:

- Loads the checkpoint.
- Adds `node` to the internal `ApprovalGate.APPROVED_KEY` set on the context. Chain `.withApproval(...)` to approve several nodes and `.withMessages(...)` to append messages.
- Re-runs from where the gate paused. The default factories see the marker and let the node run.

## Custom rule

```java
ApprovalGate gate = ApprovalGate.when(
        (node, ctx) -> {
            if (!"payment.transfer".equals(node)) return false;
            Double amount = ctx.get(AMOUNT);
            return amount != null && amount > 1000.0;
        },
        "amount above 1000 requires approval");
```

## Composition

```java
ApprovalGate payments = ApprovalGate.requireFor("payment.transfer", "refund.process");
ApprovalGate deletes  = ApprovalGate.when(
        (node, ctx) -> node.startsWith("user.delete"), "user-deletion requires approval");
ApprovalGate combined = payments.and(deletes);
```

`.and(other)` triggers approval if either side requires it; the first reason wins.

## Custom gates and the approval marker

The built-in factories (`requireFor`, `when`) check `ApprovalGate.APPROVED_KEY` to bypass an already-approved node on resume. A fully custom gate is free to ignore the marker, but then you must arrange a different bypass signal: typically a state key the operator sets via `ResumeOptions.withMessages(...)` and which the gate inspects.

## Default behaviour

`AgentGraph.Builder.approvalGate(...)` defaults to `ApprovalGate.NONE`. No interception happens — **zero overhead** when no gate is configured.

## Relationship to other policies

| Concern | Policy / gate |
|---|---|
| What tools may run | [`ToolPolicy`](tool-policy.md) |
| What state may change | [`StatePolicy`](state-policy.md) |
| What nodes need a human | `ApprovalGate` |
| What a run may spend | [`BudgetPolicy`](resilience.md#6-budget-policy-cost-gate) |

All four compose on the same graph. The `ApprovalGate` runs first (cheapest, no LLM call), then `BudgetPolicy`, then the node, then `StatePolicy` on the result.
