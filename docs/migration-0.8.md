# Migrating from 0.7 to 0.8

0.8 is the API stabilisation release on the road to 1.0: it removes duplicated and legacy entry points so that the 1.0 API can be frozen. Every change below is a compile error, not a silent behaviour change, except the three flagged **Behaviour**.

## `AgentGraph` run and resume options

Per-run settings now go through `RunOptions`, resume settings through `ResumeOptions`. `invoke(ctx)` and `resume(runId)` are unchanged.

| 0.7 | 0.8 |
|---|---|
| `graph.invoke(ctx, "run-42")` | `graph.invoke(ctx, RunOptions.ofRunId("run-42"))` |
| `graph.invoke(ctx, Duration.ofMinutes(5))` | `graph.invoke(ctx, RunOptions.ofTimeout(Duration.ofMinutes(5)))` |
| no equivalent | `RunOptions.ofRunId("run-42").withTimeout(Duration.ofMinutes(5))` |
| `graph.resumeWithApproval("run-42", "transfer")` | `graph.resume("run-42", ResumeOptions.ofApproval("transfer"))` |
| `graph.resume("run-42", msg)` | `graph.resume("run-42", ResumeOptions.ofMessages(msg))` |

`invokeStream(ctx, RunOptions)` takes the same options: a streamed run can now be checkpointed, resumed and bounded by a timeout.

## `ErrorPolicy.RETRY_ONCE` removed

```java
// 0.7
.errorPolicy(ErrorPolicy.RETRY_ONCE)
// 0.8
.retryPolicy(RetryPolicy.once())
```

**Behaviour:** `RETRY_ONCE` kept going to the next node when the retry failed too. `RetryPolicy.once()` keeps the default `FAIL_FAST`, so the run now stops. Add `.errorPolicy(ErrorPolicy.SKIP_NODE)` to keep the old behaviour.

## `RetryPolicy.retryOn` and `RetryPredicates` removed

Retry decisions are made by the `FailureClassifier` only.

| 0.7 | 0.8 |
|---|---|
| `RetryPredicates.always()` | `FailureClassifier.defaults().orElse(FailureClassifier.alwaysTransient())` |
| `RetryPredicates.transientIo()` | `FailureClassifier.defaults()` |
| `RetryPredicates.never()` | `FailureClassifier.defaults()` |
| `new RetryPolicy(n, base, max, mult, jitter, predicate)` | `new RetryPolicy(n, base, max, mult, jitter, classifier)` |
| custom `Predicate<Throwable>` | `cause -> matches(cause) ? FailureClassification.transientFailure() : null` |

**Behaviour:** a failure that no classifier recognises is now `PERMANENT`. `RetryPolicy.once().withClassifier(custom)` no longer retries unknown failures unless `custom` ends with `.orElse(FailureClassifier.alwaysTransient())`.

## Denied tool calls no longer fail the node

**Behaviour:** in 0.7, a call refused by `ToolPolicy` threw `ToolPolicyViolation`, which Spring AI does not handle: the whole `ChatClient` call failed, the node failed, and the tool calls recorded so far were lost. In 0.8 the denial is a `ToolExecutionException` whose cause is the `ToolPolicyViolation`: the model receives the denial reason as the tool result and carries on, and the denied attempt stays in `AgentResult.toolCalls()`.

If you caught `ToolPolicyViolation` around a tool callback, catch `ToolExecutionException` and read `getCause()`. To stop the run on a denial, route on the failed `ToolCallRecord` with an `Edge.onResult`.

## Internal types no longer public

`CheckpointDto`, `MessageDto`, `StateEntryDto`, `ToolCallDto` and `Node.AgentNode` are package-private. Use `CheckpointCodec` and `Node.of(...)`.

## `@Experimental` APIs

`AgentSquad`, `ParallelAgent`, `BudgetAwareRouter`, `CliAgentNode` and the `agentflow4j.squad.*` properties are marked `@Experimental`: they may change in any release before and after 1.0.

## New in 0.8

These are additions; nothing to migrate.

- `invokeStream` now enforces `ApprovalGate`, `StatePolicy` and checkpoints, like `invoke`.
- `AgentListener` gains `onCheckpoint`, `onToolCall`, `onApprovalRequired` and `onBudgetExceeded`. See [Observability](observability.md).
- `ExecutorAgent.Builder.toolProviders(...)` governs MCP tools. See [MCP tools](mcp.md).
