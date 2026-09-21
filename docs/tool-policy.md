---
description: "Restrict which tools an AI agent may call in Java/Spring with AgentFlow4J. Allow-lists, deny-lists and argument-aware rules — block shell.execute before the call leaves the agent's process."
---

# Tool Policy

A `ToolPolicy` is an allow/deny gate evaluated **before** an agent invokes a tool. It is the first concrete piece of the *governed execution* model: a small, dedicated SPI that you can ship without buying into a larger advisor framework.

## When to use it

- **Hard limits**: forbid an agent from ever calling `shell.execute` or `filesystem.write`.
- **Argument-aware rules**: refuse `payment.transfer` when the `amount` exceeds a threshold.
- **Per-agent capability scope**: the *researcher* may call `web.search`, the *writer* may not.

`ToolPolicy` is intentionally narrow. It does **not** modify arguments, sandbox the execution, or rewrite the result.

## The SPI

```java
@FunctionalInterface
public interface ToolPolicy {
    Decision check(String toolName, Map<String, Object> arguments);
    // factories: allowList(...), denyList(...), when(...), ALLOW_ALL, DENY_ALL
    // composition: policy.and(other)
}
```

`Decision` carries `allowed` plus a denial reason. When denied, the wrapping `ToolCallback` throws Spring AI's `ToolExecutionException` with the `ToolPolicyViolation` as its cause. Spring AI returns the denial reason to the model as the tool result, so the model can react (explain, ask for help, try something else), and the denied attempt is recorded as a failed `ToolCallRecord`. The real tool is never invoked.

## Attaching a policy to an executor

```java
ToolPolicy policy = ToolPolicy.allowList("web.search", "retrieval.search")
        .and(ToolPolicy.denyList("shell.execute"));

ExecutorAgent researcher = ExecutorAgent.builder()
        .name("researcher")
        .chatClient(chatClient)
        .tools(webSearchTool, retrievalTool, shellTool)
        .toolPolicy(policy)
        .build();
```

If the model tries to call `shell.execute`, the call is short-circuited before it reaches the underlying `ToolCallback`. The audit record (via `RecordingToolCallback`) captures the denied attempt with the reason.

## Composing rules

```java
ToolPolicy allowed     = ToolPolicy.allowList("web.search", "payment.transfer");
ToolPolicy noBigTransfers = ToolPolicy.when(
        (name, args) -> !("payment.transfer".equals(name)
                && args.get("amount") instanceof Number n
                && n.doubleValue() > 1000.0),
        "amount above 1000 requires approval");

ExecutorAgent agent = ExecutorAgent.builder()
        // ...
        .toolPolicy(allowed.and(noBigTransfers))
        .build();
```

`.and(other)` returns a new policy that requires both checks to pass. The first denial short-circuits — useful when one of the rules is expensive.

## Default behaviour

The default policy on an `ExecutorAgent.Builder` is `ToolPolicy.ALLOW_ALL`. No wrapping happens, so there is **zero overhead** when a policy is not configured.

## Relationship to other governance pieces

`ToolPolicy` is the first piece of a wider governance story. Companion pieces planned:

- **`StatePolicy`** — control read / write access per `StateKey<T>`.
- **`ApprovalGate`** — pause the workflow on flagged operations using the existing `InterruptRequest` + checkpoint flow.

Each policy ships independently, so adopters can pick exactly the level of governance they need.
