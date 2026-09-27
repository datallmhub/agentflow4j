# Migrating from 0.8 to 0.9

0.9 turns the sequential runtime into a parallel one. Most graphs need no change; the three points below are the exceptions.

## A second direct edge is now a branch

**Behaviour:** in 0.8, only the first direct edge out of a node was taken and the others were ignored. In 0.9 every direct edge is taken, and the branches run in parallel.

```java
.addEdge("plan", "research")
.addEdge("plan", "draft")     // 0.8: ignored. 0.9: runs in parallel with "research"
```

If you relied on the old behaviour, delete the extra edges, or make the routing explicit with `Edge.conditional`, whose first match still wins alone.

## Branches must not write the same state key

**Behaviour:** two branches writing **different** values to the same `StateKey` now fail the run with a `StateConflictException`. Give each branch its own key and let the join read them. Writing the same value from both branches is not a conflict.

## Checkpoint carries a frontier and a memo

`Checkpoint` gains `nextNodes()` and `completedNodes()`. The single-node constructor still exists, so `new Checkpoint(runId, "node", context, iterations, interrupt)` keeps compiling; `nextNode()` returns the first node of the frontier.

A custom `CheckpointStore` needs no change. A custom `CheckpointCodec` must persist both new fields, or a paused fan-out will resume on one branch and replay completed nodes. The bundled Jackson codec writes format v3 and still reads v1 and v2.

## New in 0.9

Nothing to migrate.

- `Builder.maxConcurrency(int)`, default 4.
- `Builder.onRejection(gatedNode, target)` and `ResumeOptions.ofRejection(node, reason)`.
- `ResumeOptions.withInvalidated(node)` to force a memoized node to run again.
- `RunEventType.NODE_SKIPPED` in the run log.
- See [Parallel branches](parallel.md).
