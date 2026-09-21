---
description: "Tutorial: implement human-in-the-loop approval in Java AI agents with AgentFlow4J. Pause execution, notify a human, resume from a checkpoint, end to end."
---

# Implementing human-in-the-loop approval in Java AI agents

Autonomous agents make mistakes. They misclassify inputs, call the wrong tools, or take irreversible actions (refunds, emails, database writes) based on a hallucinated context. For high-stakes workflows, the answer is not to make the agent smarter. It is to require a human to approve before the action executes.

This tutorial shows how to implement **human-in-the-loop approval** in Java with [AgentFlow4J](https://github.com/datallmhub/agentflow4j): pause an agent graph before a sensitive node, notify a human, and resume execution from a checkpoint after approval, without losing the work already done.

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
- There is no audit trail of what was approved, and when

AgentFlow4J solves this with `ApprovalGate`, a gate evaluated before a node executes. When approval is required, the graph pauses, persists a checkpoint, and returns an interrupted result. The server is free. The state is safe. A human approves asynchronously. The graph resumes exactly where it left off.

---

## Setup

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.datallmhub.agentflow4j</groupId>
        <artifactId>agentflow4j-starter</artifactId>
        <version>v0.8.0</version>
    </dependency>
    <dependency>
        <groupId>com.github.datallmhub.agentflow4j</groupId>
        <artifactId>agentflow4j-checkpoint</artifactId>
        <version>v0.8.0</version>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
</dependencies>
```

The checkpoint store needs a datasource. Any JDBC-compatible database works:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/agentflow
    username: ${DB_USER}
    password: ${DB_PASS}
```

The examples below share these typed state keys:

```java
public final class PaymentKeys {
    public static final StateKey<Double> AMOUNT     = StateKey.of("payment.amount", Double.class);
    public static final StateKey<String> RECIPIENT  = StateKey.of("payment.recipient", String.class);
    public static final StateKey<String> RISK_LEVEL = StateKey.of("payment.risk", String.class);
}
```

---

## Step 1: Define the gate

`ApprovalGate` is a functional interface. The simplest factory requires approval for specific node names:

```java
// Require approval before the "transfer" and "delete" nodes
ApprovalGate gate = ApprovalGate.requireFor("transfer", "delete");
```

For dynamic rules, where approval is needed only when a condition holds, use `ApprovalGate.when`:

```java
// Require approval only for amounts above 500
ApprovalGate gate = ApprovalGate.when(
    (node, ctx) -> "transfer".equals(node) && ctx.get(AMOUNT) > 500.0,
    "Transfer above 500 requires manager sign-off");
```

Compose several gates with `.and()`:

```java
ApprovalGate gate = ApprovalGate.requireFor("transfer")
    .and(ApprovalGate.when(
        (node, ctx) -> "HIGH".equals(ctx.get(RISK_LEVEL)),
        "High-risk transaction"));
```

---

## Step 2: Wire the gate into the graph

Gates are configured on the graph, not on individual agents. This keeps agent code free of governance concerns:

```java
@Configuration
class PaymentGraphConfig {

    @Bean
    CheckpointStore checkpointStore(DataSource dataSource, PlatformTransactionManager txManager) {
        // Register every custom state type the graph writes, so checkpoints can be serialized
        StateTypeRegistry types = new StateTypeRegistry()
            .register(AMOUNT)
            .register(RECIPIENT)
            .register(RISK_LEVEL);
        JdbcCheckpointStore store = new JdbcCheckpointStore(
            new JdbcTemplate(dataSource), txManager, new JacksonCheckpointCodec(types));
        store.createTableIfMissing();
        return store;
    }

    @Bean
    RunLogStore runLogStore() {
        return new InMemoryRunLogStore();
    }

    @Bean
    AgentGraph paymentGraph(Agent classifier, Agent transferAgent, Agent confirmationAgent,
                            CheckpointStore checkpointStore, RunLogStore runLogStore,
                            ApprovalNotifier approvalNotifier) {
        return AgentGraph.builder()
            .name("payment-workflow")
            .addNode("classify", classifier)
            .addNode("transfer", transferAgent)
            .addNode("confirm",  confirmationAgent)
            .addEdge("classify", "transfer")
            .addEdge("transfer", "confirm")
            // the gate fires before "transfer" executes
            .approvalGate(ApprovalGate.requireFor("transfer"))
            // the checkpoint lets the run resume, even after a restart
            .checkpointStore(checkpointStore)
            // the run log is the audit trail
            .runLog(runLogStore)
            // notifies the approver when the gate fires (Step 4)
            .listener(approvalNotifier)
            .build();
    }
}
```

`InMemoryRunLogStore` is fine for a demo; implement `RunLogStore` against your database to keep the audit trail across restarts.

---

## Step 3: Start the run and handle the interrupted result

Give each run an id: it is the handle you resume with later. When the gate fires, `invoke` returns immediately with `result.isInterrupted() == true`:

```java
@Service
public class PaymentService {

    private final AgentGraph graph;
    private final CheckpointStore checkpoints;

    PaymentService(AgentGraph graph, CheckpointStore checkpoints) {
        this.graph = graph;
        this.checkpoints = checkpoints;
    }

    public PaymentResult submit(Payment payment) {
        String runId = "payment-" + payment.id();
        AgentContext ctx = AgentContext.of("Pay " + payment.recipient())
            .with(AMOUNT, payment.amount())
            .with(RECIPIENT, payment.recipient())
            .with(RISK_LEVEL, payment.riskLevel());

        AgentResult result = graph.invoke(ctx, RunOptions.ofRunId(runId));

        if (result.isInterrupted()) {
            return PaymentResult.pendingApproval(runId);
        }
        return PaymentResult.completed(result.text());
    }

    public PaymentResult approve(String runId, String approver) {
        AgentResult result = graph.resume(runId, ResumeOptions.ofApproval("transfer")
            .withMessages(new UserMessage("Transfer approved by " + approver)));
        if (result.isInterrupted()) {
            // another gate further down the graph is waiting for approval
            return PaymentResult.pendingApproval(runId);
        }
        return PaymentResult.completed(result.text());
    }

    public PaymentResult reject(String runId, String reason) {
        // a rejected run is simply never resumed: drop its checkpoint
        checkpoints.delete(runId);
        return PaymentResult.rejected(reason);
    }
}
```

`ResumeOptions.ofApproval("transfer")` names the **node** being approved. The approver's identity travels as a message: it becomes part of the checkpointed conversation, visible to the downstream agents.

---

## Step 4: Notify the approver

Rather than notifying from the service, react to the gate itself with a listener. `onApprovalRequired` fires whenever a gate pauses a run, whatever code started it:

```java
@Component
class ApprovalNotifier implements AgentListener {

    private final NotificationService notifications;

    ApprovalNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void onApprovalRequired(String graphName, ApprovalRequest request) {
        notifications.requestApproval(request.nodeName(), request.reason());
    }
}
```

To include the run id in the message, read it from the checkpoint in `onCheckpoint`, which fires right after the paused run is persisted and carries `checkpoint.runId()`.

---

## Step 5: Expose approval endpoints

Wire the service into a REST controller. The approver clicks a link in their email or Slack message that hits one of these endpoints:

```java
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final PaymentService paymentService;

    ApprovalController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{runId}/approve")
    public ResponseEntity<String> approve(@PathVariable String runId,
                                          @AuthenticationPrincipal UserDetails user) {
        PaymentResult result = paymentService.approve(runId, user.getUsername());
        return ResponseEntity.ok(result.toString());
    }

    @PostMapping("/{runId}/reject")
    public ResponseEntity<String> reject(@PathVariable String runId, @RequestParam String reason) {
        paymentService.reject(runId, reason);
        return ResponseEntity.ok("Payment rejected.");
    }
}
```

Protect both endpoints with Spring Security so only users with the right role can approve.

---

## What happens on resume

When `graph.resume(runId, ResumeOptions.ofApproval("transfer"))` is called:

1. The checkpoint store loads the persisted context for this run
2. `transfer` is added to the approval marker on the context
3. The graph continues from the checkpointed node: `classify` does **not** re-execute
4. The gate re-evaluates, sees the approval marker and lets `transfer` run
5. `transfer` executes, then `confirm`, then the graph completes and the checkpoint is deleted

If the server restarted between submission and approval, nothing is lost: the checkpoint store holds the full context.

---

## Audit trail

With a `RunLogStore` configured, every step of the run is recorded under its run id:

```java
for (AgentRunEvent event : graph.runLog(runId)) {
    System.out.println(event.describe());
}
```

```text
#0 NODE_ENTER node=classify
#1 NODE_EXIT node=classify (412ms)
#2 TRANSITION node=classify — classify→transfer
#3 NODE_ENTER node=transfer
#4 APPROVAL_REQUIRED node=transfer — approval.required:transfer
#5 GRAPH_COMPLETE — approval required at transfer
```

The resumed run appends its events to the same log, numbered from `#0` again. The run log records what the graph did; record who approved in your own audit table from `PaymentService.approve`.

---

## Testing without a real approver

Use `MockAgent` from `agentflow4j-test` and the in-memory store to unit-test approval flows:

```java
@Test
void transferWaitsForApproval() {
    AgentGraph graph = AgentGraph.builder()
        .addNode("transfer", MockAgent.returning("ok"))
        .approvalGate(ApprovalGate.requireFor("transfer"))
        .checkpointStore(new InMemoryCheckpointStore())
        .build();

    AgentResult first = graph.invoke(AgentContext.of("transfer 600"), RunOptions.ofRunId("run-1"));
    assertThat(first.isInterrupted()).isTrue();
    assertThat(first.interrupt().reason()).isEqualTo("approval.required:transfer");

    AgentResult resumed = graph.resume("run-1", ResumeOptions.ofApproval("transfer"));
    assertThat(resumed.isInterrupted()).isFalse();
    assertThat(resumed.text()).isEqualTo("ok");
}
```

No HTTP, no database, no Slack: the test runs in milliseconds.

---

## Next steps

- **[Approval via Slack](../recipes/approval-via-slack.md)**: send an interactive Slack message with Approve / Reject buttons, ~30 lines
- **[Stop your agent burning $1000 overnight](stop-your-agent-burning-money.md)**: combine `ApprovalGate` with `BudgetPolicy` and `ToolPolicy`
- **[Observability](../observability.md)**: every lifecycle hook, including `onApprovalRequired`
