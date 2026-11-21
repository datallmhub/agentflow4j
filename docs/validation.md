---
description: "What AF4J guarantees and how far each guarantee is proven: the test behind every governance claim, and what that test does not prove."
---

# Validation

What AF4J guarantees, and how far each guarantee is proven. Every entry names the test that checks it and says what that test does not prove. No claim without its limit.

## Evidence levels

| Level | What it means |
|---|---|
| **Checked by test** | An automated test in this repository asserts the behaviour. It runs on every push and pull request, on JDK 17 and 21, and again against Spring AI 1.1 and 2.1. |
| **Checked against a stand-in** | Tested, but against an in-memory or mocked dependency instead of the real one. |
| **Documented, not tested** | Implemented and documented, with no dedicated test asserting it yet. |

The test names below link to the source. `graph`, `squad` and `checkpoint` are the modules they live in.

[graph-tests]: https://github.com/datallmhub/agentflow4j/tree/main/agentflow4j-graph/src/test/java/io/github/datallmhub/agentflow4j/graph
[squad-tests]: https://github.com/datallmhub/agentflow4j/tree/main/agentflow4j-squad/src/test/java/io/github/datallmhub/agentflow4j/squad
[checkpoint-tests]: https://github.com/datallmhub/agentflow4j/tree/main/agentflow4j-checkpoint/src/test/java/io/github/datallmhub/agentflow4j/checkpoint

## Guarantees

### G-01. A denied tool call never reaches the tool

**Checked by test.** [`PolicyToolCallbackTests`][squad-tests]`.deniesCallAndDoesNotInvokeDelegateWhenPolicyRefuses`, and for MCP tools [`McpToolCompatibilityTests`][squad-tests]`.mcpToolsGoThroughToolPolicyAndAudit`, which verifies that the MCP server never receives the denied call.

**Limit.** The policy covers tools registered on the `ExecutorAgent`. Tools registered directly on the `ChatClient` bypass it. A rule matches on the name the tool is exposed under, and that name can change with the Spring AI line: see [MCP tools](mcp.md).

### G-02. Every tool call is recorded, allowed or denied

**Checked by test.** [`RecordingToolCallbackTests`][squad-tests]`.successCallIsRecordedWithParsedArgsAndResult` and `.failureCallIsRecordedWithErrorAndRethrows`. The denied MCP call in G-01 is asserted to be on the record as a failed call.

**Limit.** The record is returned on the node's `AgentResult` and sent to listeners. Keeping it beyond the run is the application's job.

### G-03. A budget stops the run before the next node

**Checked by test.** [`AgentGraphBudgetTests`][graph-tests]`.runBudgetStopsGraphAcrossNodes`, `.nodeBudgetStopsSecondAttemptOfSameNode` and `.callBudgetFailsFastBeforeNodeIsEverInvoked`.

**Limit.** The budget is checked before a node runs, not while the model is answering. A call that turns out more expensive than estimated is recorded after the fact, and it is the next call that is refused. That a budget is shared safely across parallel branches is **documented, not tested**.

### G-04. A gated node does not run until it is approved

**Checked by test.** [`AgentGraphApprovalGateTests`][graph-tests]`.gateInterruptsBeforeFlaggedNodeAndPersistsCheckpoint`, `.unapprovedNodesStayGated` and `.resumeWithApprovedNodeBypassesGateAndCompletesNode`. The streaming path is covered by [`AgentGraphStreamGovernanceTests`][graph-tests]`.approvalGateInterruptsStreamBeforeFlaggedNode`.

**Limit.** AF4J records that a node was approved, not who approved it. Deciding who may call `resume` belongs to the application, for example with Spring Security. Resuming needs a `CheckpointStore`.

### G-05. An approval pauses only the branch it gates

**Checked by test.** [`AgentGraphParallelTests`][graph-tests]`.approvalPausesOnlyTheGatedBranch` and `.aJoinWaitsForABranchHeldBackByApproval`.

**Limit.** In streaming mode branches are visited one after another, so nothing runs concurrently there.

### G-06. A rerun does not repeat a node that already ran

**Checked by test.** [`AgentGraphRejectionTests`][graph-tests]`.rejectionReroutesWithoutReplayingSideEffects` and `.invalidatingANodeMakesItRunAgain`.

**Limit.** The protection is per node. A node that crashes halfway through its own side effect is not covered: making one node idempotent is still the node's job.

### G-07. Two branches cannot silently overwrite each other

**Checked by test.** [`AgentGraphParallelTests`][graph-tests]`.conflictingWritesFailTheRun`, `.identicalWritesFromTwoBranchesAreNotAConflict` and `.stateOfBothBranchesIsMergedAndTheJoinRunsOnce`.

**Limit.** The conflict is detected when the branches join, so both branches have already run and spent their tokens by then.

### G-08. A denied state write is not applied

**Checked by test.** [`AgentGraphStatePolicyTests`][graph-tests]`.deniedWriteSurfacesAsAgentErrorUnderFailFast` and `.firstDeniedKeyShortCircuitsTheRest`.

**Limit.** The policy is write-only by design: reading a key is never intercepted. It also cannot see what a node does outside the graph state.

### G-09. A budget or permanent failure is not retried

**Checked by test.** [`AgentGraphReasonAwareRetryTests`][graph-tests]`.budgetExceededExceptionInterruptsRunInsteadOfRetrying`, `.permanentFailureIsNotRetriedUnderDefaultClassifier` and `.transientIoFailureIsRetriedUnderDefaultClassifier`.

**Limit.** Spring's HTTP exceptions are recognised by class name. A failure no classifier recognises is treated as permanent, so it is not retried unless you say so.

### G-10. A paused run survives a restart

**Checked against a stand-in.** [`CheckpointResumeE2ETests`][checkpoint-tests]`.jdbcStoreSurvivesInterruptAndResume` and `.redisStoreSurvivesInterruptAndResume` pause a run, build a new graph over the same store and resume it.

**Limit.** The JDBC test runs on in-memory H2 and the Redis test on a mocked client. Neither PostgreSQL, MySQL nor a real Redis is exercised in this repository, and no test kills an actual process.

## Compatibility

**Checked by test.** The published modules are rebuilt and tested in CI against three lines:

| Spring AI | Spring Boot |
|---|---|
| 1.0.9 | 3.5 |
| 1.1.8 | 3.5 |
| 2.1.0 | 4.2 |

**Limit.** Each line is checked by recompiling the source against it. The published jars are compiled against Spring AI 1.0.9, and a single jar has not been proven to run unchanged on the other two lines. The samples are only built on the baseline.

## The numbers behind this page

- 330 automated tests in the ten published modules, run on every build.
- The build fails under 80% line coverage, measured per module with JaCoCo. The playground and the samples are exempt.

## What is not validated

- **Performance.** There is no throughput or latency benchmark.
- **Real datastores.** See G-10.
- **A production track record.** One real system builds on AF4J, the investigation agent of [secANTy](https://secanty.com), an application security platform. No production statistics are published.
- **Long waits and compensation.** Durable timers, compensation and waits that span days are not provided. A workflow engine is the right tool for those.
