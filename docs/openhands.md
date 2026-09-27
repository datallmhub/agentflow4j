# OpenHands

`OpenHandsAgent` delegates a coding task to [OpenHands](https://docs.openhands.dev) from a graph node. af4j does not reimplement the agent: it governs it, so the coding run gets an approval gate, a budget, a checkpoint and an audit trail like any other node.

```xml
<dependency>
    <groupId>com.github.datallmhub.agentflow4j</groupId>
    <artifactId>agentflow4j-openhands</artifactId>
    <version>v0.10.0</version>
</dependency>
```

The module talks to the OpenHands V1 conversation API over the JDK `HttpClient`, with a connect and a request timeout on every call, and carries no Spring Web dependency. The same client works against OpenHands Cloud, the enterprise edition and a self-hosted server.

## A governed coding node

```java
OpenHandsAgent coder = OpenHandsAgent.builder()
        .name("code")
        .client(OpenHandsClient.cloud(System.getenv("OPENHANDS_API_KEY")))
        .repository("acme/billing")
        .task(ctx -> ctx.get(TICKET))     // default: the last user message
        .maxCost(5.00)                    // pause the sandbox above this
        .build();

AgentGraph graph = AgentGraph.builder()
        .addNode("triage", triageAgent)
        .addNode("code", coder)
        .addNode("announce", announcer)
        .addEdge("triage", "code")
        .addEdge("code", "announce")
        .approvalGate(ApprovalGate.requireFor("code"))   // a human approves before paying
        .checkpointStore(checkpointStore)                 // required in ASYNC mode
        .build();
```

## Async by default

A coding task runs for minutes or hours, and OpenHands caps a session at 12 hours. Blocking a thread for that is not an option, so the node is asynchronous by default:

1. the node starts the conversation, writes its id to the state and returns the interrupt `openhands.running:<id>`;
2. the graph checkpoints and the thread is free;
3. a later `graph.resume(runId)`, from a scheduler or your own webhook, polls once: still working means another interrupt, finished means the result.

The run survives a restart, because everything needed sits in the checkpoint. `Mode.SYNC` polls until the task is terminal or `maxWait` elapses; it is simpler and fine for short tasks.

## Never two conversations for one task

The conversation id in `OpenHandsKeys.CONVERSATION_ID` is the idempotency key: while it is in the state, the node polls that conversation instead of starting another one. A resume, a retry, or a rerun after a rejected approval therefore costs nothing extra. It holds even when the node failed: a timeout still keeps the id.

## What the node reports

| OpenHands status | Node result |
|---|---|
| `finished` | completed, with the agent's last message as text |
| `running`, `idle`, `paused`, starting | `openhands.running:<id>` interrupt (ASYNC), or another poll (SYNC) |
| `waiting_for_confirmation` | `openhands.confirmation:<id>` interrupt: a human confirms in the OpenHands UI, then resume |
| `stuck` | `openhands.stuck:<id>` interrupt |
| `error`, or a lost sandbox | node failure, so `ErrorPolicy` and `RetryPolicy` decide |
| cost above `maxCost` | the sandbox is paused, then a `budget.exceeded:<node>` interrupt |

Every interrupt carries the `OpenHandsConversation` as its payload, so a listener can show the branch, the pull request and the cost.

State keys written by the node: `CONVERSATION_ID`, `SANDBOX_ID`, `BRANCH`, `PULL_REQUEST` and `COST`. Token usage lands in `AgentResult.usage()`.

## Retries

API failures are classified by `OpenHandsFailureClassifier`, so a node retries what is worth retrying:

```java
.retryPolicy(RetryPolicy.exponential(3, Duration.ofSeconds(2))
        .withClassifier(OpenHandsFailureClassifier.INSTANCE.orElse(FailureClassifier.defaults())))
```

`429` and `5xx` are transient, `402` and `403` are over budget, other `4xx` are permanent, and a transport failure is transient.

## Limits

- OpenHands exposes no completion webhook, so progress is polled. Pick `pollInterval` to match how long your tasks run.
- A confirmation cannot be answered through the API: the operator confirms in the OpenHands UI, then the run is resumed.
- On OpenHands Cloud, too many concurrent conversations pause the oldest ones, and a session is capped at 12 hours.
