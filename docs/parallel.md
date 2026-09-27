# Parallel branches

A node with several outgoing direct edges fans out: its successors form one **frontier** and run at the same time. The nodes they lead to join that frontier, so a diamond runs its two sides in parallel and its join once.

```java
AgentGraph graph = AgentGraph.builder()
        .addNode("plan", planner)
        .addNode("market", marketResearcher)
        .addNode("tech", techResearcher)
        .addNode("report", writer)
        .addEdge("plan", "market")      // two direct edges out of "plan"
        .addEdge("plan", "tech")        // become two parallel branches
        .addEdge("market", "report")    // both join on "report"
        .addEdge("tech", "report")
        .maxConcurrency(4)              // branches running at once, default 4
        .build();
```

Nothing changes for a sequential graph: a node with a single direct edge behaves exactly as before, and conditional routing is unchanged. An `Edge.conditional` or `Edge.onResult` whose predicate matches still selects one successor; the direct edges are the fan-out.

## Merging state

Each branch starts from the state at the fork and its updates are merged when the frontier completes. Two branches writing **different** values to the same `StateKey` fail the run with a `StateConflictException` naming the key and both nodes, so a result never depends on which branch finished first. Writing the same value from both branches is not a conflict.

Give each branch its own keys, and let the join read them:

```java
static final StateKey<String> MARKET = StateKey.of("research.market", String.class);
static final StateKey<String> TECH   = StateKey.of("research.tech", String.class);
```

## Governance across branches

| Concern | Behaviour |
|---|---|
| `ApprovalGate` | Pauses **only** the gated node. The other branches of the frontier finish, their state is checkpointed, and `resume(runId, ResumeOptions.ofApproval(node))` runs the gated node without replaying the branches that already ran |
| `BudgetPolicy` | Shared across branches: the counters are thread-safe, so a per-run cap bounds the whole fan-out |
| `ErrorPolicy.FAIL_FAST` | A failing branch fails the run; the other branches of the same frontier still complete first |
| `RunLogStore` | Every node of the frontier is recorded, so the log shows what ran in parallel |
| Checkpoints | A checkpoint stores the whole frontier (`Checkpoint.nextNodes()`), which is why a paused fan-out resumes correctly |

## Rejecting an approval

`resume(runId, ResumeOptions.ofRejection(node, reason))` sends the run to the node declared by `onRejection(gatedNode, target)`; without a route, the run ends with an `approval.rejected:<node>` interrupt and the checkpoint is dropped.

```java
AgentGraph.builder()
        .addNode("plan", planner)
        .addNode("charge", paymentGateway)      // side effect
        .addNode("payment.transfer", transfer)
        .addEdge("plan", "charge")
        .addEdge("charge", "payment.transfer")
        .approvalGate(ApprovalGate.requireFor("payment.transfer"))
        .onRejection("payment.transfer", "plan") // rerun from the top
        .checkpointStore(store)
        .build();
```

A rerun that goes back through nodes that already ran does **not** repeat them: the checkpoint carries a memo of completed nodes, and a memoized node is skipped and recorded as `NODE_SKIPPED` in the run log. That is what keeps `charge` from charging twice.

Each memo entry is consumed the first time it is skipped, so a loop still revisits its node on later iterations. To force a node to run again, name it:

```java
graph.resume(runId, ResumeOptions.ofRejection("payment.transfer", "wrong amount")
        .withInvalidated("plan"));
```

A skipped node keeps the state of its first run, and `Edge.onResult` predicates cannot inspect a result that was not produced again: route reruns on state, not on the skipped node's output.

## Streaming

`invokeStream` visits branches one after another, in declaration order: a single ordered event stream cannot interleave concurrent nodes. The result is the same, the execution is not concurrent.

## Limits

- A frontier is a barrier: the next frontier starts when every node of the current one has finished, so a slow branch delays the join even if another branch could already move on.
- `maxIterations` counts frontiers, not nodes.
