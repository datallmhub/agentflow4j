---
description: "Tutorial: stop your AI agent from burning $1000 overnight on a paid API or running shell.execute. End-to-end walkthrough of AgentFlow4J's four governance gates — budget, tool, state, approval — in Java/Spring."
---

# Stop your AI agent from burning $1000 overnight

Most Java agent frameworks treat the agent as fully trusted: it can call any tool, mutate any state, and loop on a paid API as many times as it likes. That's fine in a demo. In production it's a money leak — and occasionally a security incident.

Here's a real failure mode that motivated this tutorial: a five-agent system ran for 90 days. One night, a single agent retried a paid API **23 times** against a transient error, at $0.05/call, before anyone noticed. Small money — but the retry policy was *blind to cost*. Multiply by a bigger price tag or a `shell.execute` tool and it stops being funny.

This tutorial shows how to make an agent **governed by default** with [AgentFlow4J](https://github.com/datallmhub/agentflow4j): cap its spend, block dangerous tools, protect sensitive state, and require a human signature before high-stakes actions — without writing orchestration glue.

We'll build a customer-support agent that can issue refunds, then lock it down step by step.

## Setup

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.datallmhub.agentflow4j</groupId>
    <artifactId>agentflow4j-starter</artifactId>
    <version>v0.7.0</version>
</dependency>
```

## 1. The ungoverned version

A graph that triages a ticket, then runs a node that can transfer money. Nothing stops it from spending or acting:

```java
AgentGraph graph = AgentGraph.builder()
        .addNode("triage", triageAgent)
        .addNode("refund", refundAgent)        // can call payment.transfer
        .addEdge("triage", "refund")
        .build();

graph.invoke(AgentContext.of("Refund my duplicate $5000 charge"));
```

If the LLM hallucinates a $5000 transfer, or retries a paid lookup 20 times, you find out on the invoice.

## 2. Cap the spend — `BudgetPolicy`

A `BudgetPolicy` gates **before every call, including retries**. Limits are currency-agnostic — you supply a cost estimator and meter, so "cost" can be dollars, tokens, or call counts.

```java
double dollarsPerToken = 0.002 / 1000.0;
CostEstimator estimator = (node, ctx) ->
        ctx.messages().stream().mapToInt(m -> m.getText().length() / 4).sum() * dollarsPerToken;
CostMeter meter = CostMeter.totalTokens().scaledBy(dollarsPerToken);

BudgetPolicy budget = BudgetPolicy.hierarchical(
        BudgetLimits.builder()
                .perRun(2.00)    // $2.00 hard cap for the whole run
                .perNode(0.50)   // no single node may burn more than $0.50
                .perCall(0.10)   // refuse any single call above $0.10
                .build(),
        estimator, meter);

AgentGraph.builder()
        // ... nodes ...
        .budgetPolicy(budget)
        .build();
```

A $5 call against a $2 budget now **fails fast** instead of executing. The breach surfaces as an `InterruptRequest` (reason `budget.exceeded:RUN`), so you can pause, alert, and resume after raising the limit rather than just crashing.

## 3. Block dangerous tools — `ToolPolicy`

Tool access is gated on the agent that owns the tools. Allow what you trust, deny the rest:

```java
ExecutorAgent refundAgent = ExecutorAgent.builder()
        .chatClient(chatClient)
        .tools(refundLookupTool, shellTool)
        .toolPolicy(
                ToolPolicy.allowList("refund.lookup", "refund.process")
                        .and(ToolPolicy.denyList("shell.execute")))
        .build();
```

If the model is talked into calling `shell.execute("rm -rf /")` (prompt injection is real), the call is short-circuited before it reaches the tool, and the denial is recorded in the audit trail.

You can also inspect arguments:

```java
ToolPolicy noBigRefunds = ToolPolicy.when(
        (name, args) -> !("refund.process".equals(name)
                && args.get("amount") instanceof Number n && n.doubleValue() > 1000.0),
        "refunds above 1000 require approval");
```

## 4. Protect sensitive state — `StatePolicy`

Agents share typed state via `StateKey<T>`. A `StatePolicy` controls which keys a node may write:

```java
AgentGraph.builder()
        // ...
        .statePolicy(StatePolicy.denyWriteKeys("payment.confirmed", "account.balance"))
        .build();
```

Now no node can flip `payment.confirmed` to `true` on its own — only a dedicated, reviewed node may. A denied write surfaces as an error the graph's `ErrorPolicy` handles (fail fast, retry, or skip).

## 5. Require a human — `ApprovalGate`

For genuinely high-stakes actions, pause and wait for a person. This reuses the framework's checkpoint mechanism, so the workflow survives a restart while it waits:

```java
AgentGraph graph = AgentGraph.builder()
        .addNode("refund", refundAgent)
        .addNode("payment.transfer", transferAgent)
        .addEdge("refund", "payment.transfer")
        .approvalGate(ApprovalGate.requireFor("payment.transfer"))
        .checkpointStore(new InMemoryCheckpointStore())   // or JDBC / Redis
        .build();

AgentResult result = graph.invoke(ctx, "ticket-4521");

if (result.isInterrupted()) {
    ApprovalRequest req = (ApprovalRequest) result.interrupt().payload();
    notifySlack("Approve transfer for " + req.nodeName() + "? " + req.reason());
}
```

When the human clicks **Approve**, resume from the exact checkpoint:

```java
AgentResult done = graph.resumeWithApproval("ticket-4521", "payment.transfer");
```

Reject? Just don't resume. The money never moved.

## Putting it together

```java
ExecutorAgent transferAgent = ExecutorAgent.builder()
        .chatClient(chatClient)
        .tools(transferTool)
        .toolPolicy(ToolPolicy.denyList("shell.execute"))      // 1. tools
        .build();

AgentGraph graph = AgentGraph.builder()
        .addNode("triage", triageAgent)
        .addNode("payment.transfer", transferAgent)
        .addEdge("triage", "payment.transfer")
        .statePolicy(StatePolicy.denyWriteKeys("payment.confirmed"))   // 2. state
        .budgetPolicy(budget)                                          // 3. cost
        .approvalGate(ApprovalGate.requireFor("payment.transfer"))     // 4. human
        .checkpointStore(store)
        .build();
```

Four gates, four lines. Each one is opt-in and a no-op by default, so you adopt exactly the level of control you need.

## Why this matters

Production AI workflows are long-running, stateful, and capable of side effects. "It worked in the demo" is not a safety model. Governed execution turns *hope the agent behaves* into *the runtime enforces it* — which is the difference between a prototype and something you can put in front of real customers and real money.

Full runnable example: [`SupportTriageDemo`](https://github.com/datallmhub/agentflow4j/blob/main/agentflow4j-samples/src/main/java/io/github/datallmhub/agentflow4j/samples/SupportTriageDemo.java) in the repo. Docs: [Tool policy](../tool-policy.md) · [State policy](../state-policy.md) · [Approval gate](../approval-gate.md) · [Budget policy](../resilience.md#6-budget-policy-cost-gate).

If this is useful, a ⭐ on [the repo](https://github.com/datallmhub/agentflow4j) helps others find it.
