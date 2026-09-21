# Approval via Slack — async, non-blocking

The point of `ApprovalGate` is *not* "block the workflow until a human clicks". When the gate fires, the run **persists a checkpoint and exits**. Your code is free to notify whatever channel you want; the agent is parked, not waiting on a thread.

This recipe wires `ApprovalGate` to a Slack message with an "Approve" link. Total surface: one `@RestController`, one notifier, ~30 lines of code.

## How it flows

```
ticket comes in  →  graph.invoke()  →  gate fires on "refund.process"
                                   →  checkpoint saved
                                   →  AgentResult.interrupted(ApprovalRequest)
                                   →  Slack message: "Approve refund? <link>"
                       process exits, no thread held

human clicks link  →  GET /approve/{runId}/{node}
                  →  graph.resume(runId, ResumeOptions.ofApproval(node))
                  →  refund.process runs from the checkpoint
                  →  customer gets the refund
```

## The whole thing

```java
@SpringBootApplication
@RestController
public class SupportApp {

    private final AgentGraph graph;
    private final SlackNotifier slack;

    SupportApp(AgentGraph graph, SlackNotifier slack) {
        this.graph = graph;
        this.slack = slack;
    }

    @PostMapping("/tickets")
    String submit(@RequestBody String body) {
        String runId = UUID.randomUUID().toString();
        AgentResult result = graph.invoke(AgentContext.of(body), RunOptions.ofRunId(runId));

        if (result.isInterrupted()) {
            ApprovalRequest req = (ApprovalRequest) result.interrupt().payload();
            slack.notify(runId, req.nodeName(), req.reason());
            return "pending approval: " + runId;
        }
        return result.text();
    }

    @GetMapping("/approve/{runId}/{node}")
    String approve(@PathVariable String runId, @PathVariable String node) {
        AgentResult result = graph.resume(runId, ResumeOptions.ofApproval(node));
        return result.completed() ? "done: " + result.text() : "still running";
    }

    @Bean
    AgentGraph graph(InMemoryCheckpointStore store) {
        return AgentGraph.builder()
                .addNode("triage", triageAgent)
                .addNode("refund.process", refundAgent)
                .addEdge("triage", "refund.process")
                .approvalGate(ApprovalGate.requireFor("refund.process"))
                .checkpointStore(store)
                .build();
    }
}

@Component
class SlackNotifier {
    private final String webhook = System.getenv("SLACK_WEBHOOK_URL");
    private final RestClient rest = RestClient.create();

    void notify(String runId, String node, String reason) {
        String url = "https://your-app.example/approve/" + runId + "/" + node;
        String msg = "*Approval needed* — `" + node + "`\n_" + reason + "_\n<" + url + "|Approve>";
        rest.post().uri(webhook)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("text", msg))
                .retrieve()
                .toBodilessEntity();
    }
}
```

That is the entire integration. No background queue, no separate worker, no UI. The "approval" is one HTTP request from Slack to your app.

## Wiring it up

1. Create a Slack incoming webhook on your workspace, copy the URL.
2. `export SLACK_WEBHOOK_URL='https://hooks.slack.com/services/...'`
3. Run the app. POST `/tickets` with a refund request — Slack pings you. Click the link, the agent finishes.

## Going further

The recipe stops at "it works". Three things to add when you put this in front of real customers:

- **Authenticate the approve link.** Wrap the URL with a signed token (JWT, HMAC) and check it on `/approve`. Otherwise anyone with the link can approve.
- **Idempotency.** If the human double-clicks, the second `resume` will run the node again. Either short-circuit when `cp.interrupt() == null` (already resumed) or make the downstream node idempotent.
- **Reject path.** Add `GET /reject/{runId}/{node}` that calls `store.delete(runId)` or transitions the workflow elsewhere. Right now "not approving" just means the checkpoint sits there.

For richer Slack UX (block kit buttons, interactivity payloads), the same pattern applies — the only thing that changes is `SlackNotifier`. The graph side stays identical.

## Why this design

This is the difference the early Reddit critique flagged: "synchronous approval kills enthusiasm". `ApprovalGate` was deliberately built so that:

- the agent's thread is never blocked waiting for a human
- the operator's latency is whatever your async channel costs (Slack, email, push), not idle compute
- the gate is opt-in per node — flows that don't need it pay nothing

See [`ApprovalGate` docs](../approval-gate.md) for the full SPI.
