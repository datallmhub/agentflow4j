---
description: "Tutorial: implement human-in-the-loop approval in Java AI agents with AgentFlow4J. Pause execution, notify a human, resume from checkpoint — end to end."
---

# Implementing human-in-the-loop approval in Java AI agents

Autonomous agents make mistakes. They misclassify inputs, call the wrong tools, or take irreversible actions — refunds, emails, database writes — based on a hallucinated context. For high-stakes workflows, the answer is not to make the agent smarter. It is to require a human to approve before the action executes.

This tutorial shows how to implement **human-in-the-loop approval** in Java with [AgentFlow4J](https://github.com/datallmhub/agentflow4j): pause an agent graph before a sensitive node, notify a human, and resume execution from a checkpoint after approval — without losing the work already done.

---

## The problem with naive approaches

The obvious approach is to put an `if` statement before the sensitive call:

```java
if (requiresApproval(context)) {
    sendEmail(approver, context);
    // ... wait? how?
}
```

This breaks immediately in production:

- You cannot block a thread waiting for an email reply
- If the server restarts while waiting, the state is lost
- The agent code is now coupled to your notification system
- There is no audit trail of what was approved, by whom, and when

AgentFlow4J solves this with `ApprovalGate` — a gate evaluated before a node executes. When approval is required, the graph pauses, persists a checkpoint, and returns an interrupted result. The server is free. The state is safe. A human approves asynchronously. The graph resumes exactly where it left off.

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

You also need a datasource for the checkpoint store — any JDBC-compatible database works:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentflow
    username: ${DB_USER}
    password: ${DB_PASS}
```

---

## Step 1 — Define the gate

`ApprovalGate` is a functional interface. The simplest factory requires approval for specific node names:

```java
// Require approval before "transfer" and "delete" nodes
ApprovalGate gate = ApprovalGate.requireFor("transfer", "delete");
```

For dynamic rules — approval only when a condition is true — use `ApprovalGate.when`:

```java
// Require approval only for amounts above €500
ApprovalGate gate = ApprovalGate.when(
    (node, ctx) -> "transfer".equals(node)
        && ctx.get(AMOUNT) > 500.0,
    "Transfer above €500 requires manager sign-off");
```

Compose multiple gates with `.and()`:

```java
ApprovalGate gate = ApprovalGate.requireFor("transfer")
    .and(ApprovalGate.when(
        (node, ctx) -> "HIGH".equals(ctx.get(RISK_LEVEL)),
        "High-risk transaction"));
```

---

## Step 2 — Wire the gate into the graph

Gates are configured on the graph, not on individual agents. This keeps agent code free of governance concerns:

```java
@Bean
AgentGraph paymentGraph(
        Agent classifier,
        Agent transferAgent,
        Agent confirmationAgent,
        DataSource dataSource) {

    return AgentGraph.builder()
        .name("payment-workflow")
        .addNode("classify",  classifier)
        .addNode("transfer",  transferAgent)
        .addNode("confirm",   confirmationAgent)
        .addEdge("classify",  "transfer")
        .addEdge("transfer",  "confirm")
        // gate fires before "transfer" executes
        .approvalGate(ApprovalGate.requireFor("transfer"))
        // checkpoint persists state so resume works after a restart
        .checkpointStore(new JdbcCheckpointStore(dataSource))
        .build();
}
```

---

## Step 3 — Handle the interrupted result

When the gate fires, `graph.invoke()` returns immediately with `result.isInterrupted() == true`. The run ID is your handle for resuming later:

```java
@Service
public class PaymentService {

    private final AgentGraph graph;
    private final NotificationService notifications;

    public PaymentResult submit(Payment payment) {
        AgentContext ctx = AgentContext.builder()
            .with(AMOUNT,      payment.amount())
            .with(RECIPIENT,   payment.recipient())
            .with(RISK_LEVEL,  payment.riskLevel())
            .build();

        AgentResult result = graph.invoke(ctx);

        if (result.isInterrupted()) {
            // Store run ID — you will need it to resume
            String runId = result.runId();

            // Notify the approver (email, Slack, webhook — your choice)
            notifications.requestApproval(
                runId,
                payment,
                result.interruptReason());

            return PaymentResult.pendingApproval(runId);
        }

        return PaymentResult.completed(result.context().get(CONFIRMATION_ID));
    }

    public PaymentResult approve(String runId, String approverName) {
        AgentResult result = graph.resumeWithApproval(runId, approverName);
        return PaymentResult.completed(result.context().get(CONFIRMATION_ID));
    }

    public PaymentResult reject(String runId, String approverName, String reason) {
        AgentResult result = graph.resumeWithRejection(runId, approverName, reason);
        return PaymentResult.rejected(reason);
    }
}
```

---

## Step 4 — Expose approval endpoints

Wire the service into a REST controller. The approver clicks a link in their email or Slack message that hits one of these endpoints:

```java
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final PaymentService paymentService;

    @PostMapping("/{runId}/approve")
    public ResponseEntity<String> approve(
            @PathVariable String runId,
            @AuthenticationPrincipal UserDetails user) {
        PaymentResult result = paymentService.approve(runId, user.getUsername());
        return ResponseEntity.ok("Payment completed: " + result.confirmationId());
    }

    @PostMapping("/{runId}/reject")
    public ResponseEntity<String> reject(
            @PathVariable String runId,
            @AuthenticationPrincipal UserDetails user,
            @RequestParam String reason) {
        paymentService.reject(runId, user.getUsername(), reason);
        return ResponseEntity.ok("Payment rejected.");
    }
}
```

Spring Security protects both endpoints — only users with the right role reach the approval page. The `user.getUsername()` is recorded in the `RunLog` for audit purposes.

---

## What happens on resume

When `graph.resumeWithApproval(runId, approver)` is called:

1. The checkpoint store loads the persisted graph state for this run
2. The approval marker is added to the context for the `transfer` node
3. `AgentGraph` replays from the last checkpoint — `classify` does **not** re-execute
4. The gate re-evaluates — it sees the approval marker and allows the node
5. `transfer` executes, then `confirm`, then the graph completes normally

If the server restarted between submission and approval, nothing is lost. The checkpoint store holds the full context.

---

## Audit trail

Every approval and rejection is recorded automatically in `RunLog`:

```java
// Read the full execution trace for a run
RunLog log = runLogStore.find(runId);

log.entries().forEach(entry -> {
    System.out.printf("[%s] %s — %s%n",
        entry.timestamp(),
        entry.event(),      // NODE_ENTER, APPROVAL_REQUESTED, APPROVAL_GRANTED, NODE_EXIT ...
        entry.detail());
});
```

You get a complete, replayable audit trail without writing a single line of logging code.

---

## Testing without a real approver

Use `MockAgent` from `agentflow4j-test` to unit-test approval flows:

```java
@Test
void payment_above_threshold_requires_approval() {
    AgentGraph graph = AgentGraph.builder()
        .addNode("transfer", MockAgent.returning("ok"))
        .approvalGate(ApprovalGate.requireFor("transfer"))
        .checkpointStore(new InMemoryCheckpointStore())
        .build();

    AgentResult first = graph.invoke(AgentContext.of("transfer €600"));
    assertThat(first.isInterrupted()).isTrue();
    assertThat(first.interruptReason()).contains("requires");

    AgentResult resumed = graph.resumeWithApproval(first.runId(), "test-approver");
    assertThat(resumed.isInterrupted()).isFalse();
    assertThat(resumed.text()).isEqualTo("ok");
}
```

No HTTP, no database, no Slack — the test runs in milliseconds.

---

## Next steps

- **[Approval via Slack](../recipes/approval-via-slack.md)** — send an interactive Slack message with Approve / Reject buttons, ~30 lines
- **[Stop your agent burning $1000 overnight](stop-your-agent-burning-money.md)** — combine `ApprovalGate` with `BudgetPolicy` and `ToolPolicy`
- **[Observability](../observability.md)** — monitor approval wait times and approval rates with Micrometer
