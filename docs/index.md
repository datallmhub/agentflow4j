---
description: "AgentFlow4J is a framework and runtime for governed, stateful multi-agent systems on the JVM. Human approvals, checkpoints, budget controls, tool policies and durable execution built for Spring."
---

# AgentFlow4J

**Build AI agents you can trust in production.**

AgentFlow4J is a framework and runtime for governed, stateful multi-agent systems on the JVM.

Human approvals · Checkpoints · Budget controls · Tool policies · Durable execution

[:material-rocket-launch: Get started](getting-started.md){ .md-button .md-button--primary }
[:material-book-open: Core concepts](concepts.md){ .md-button }
[:material-github: GitHub](https://github.com/datallmhub/agentflow4j){ .md-button }

---

## Why AgentFlow4J?

- Build multi-agent workflows with explicit orchestration
- Persist execution across failures and restarts
- Add human approval where it matters
- Control tool access and AI spend
- Run natively on the JVM and Spring ecosystem

---

## Core capabilities

| Capability | What it does | AgentFlow4J |
|---|---|---|
| **Multi-agent orchestration** | Build agent teams with routing and fan-out | `AgentGraph`, `CoordinatorAgent`, `ParallelAgent` |
| **Governance** | Control what agents can call, change, or spend | `ToolPolicy`, `StatePolicy`, `BudgetPolicy`, `ApprovalGate` |
| **Durable execution** | Survive restarts, resume from last checkpoint | `JdbcCheckpointStore`, `RedisCheckpointStore` |
| **Human-in-the-loop** | Pause before critical actions, resume on approval | `ApprovalGate` |
| **Resilience** | Classify failures, retry smart, route to fallback | `RetryPolicy`, `FailureClassifier`, `BudgetAwareRouter` |
| **Observability** | Metrics, run logs, streaming events | Micrometer, `RunLog`, `Flux<AgentEvent>` |

---

## Try the demo in 5 minutes

```bash
git clone https://github.com/datallmhub/agentflow4j.git
cd agentflow4j
mvn install -DskipTests -q
mvn -pl agentflow4j-samples exec:java
```

Runs `SupportTriageDemo`: a governed multi-agent workflow with `ToolPolicy` and `ApprovalGate` active. No API key required.

---

## Where to next

- **New here?** Start with [Core concepts](concepts.md).
- **Ready to code?** Follow the [Getting started](getting-started.md) guide.
- **Want runnable examples?** The [Cookbook](https://github.com/datallmhub/agentflow4j-cookbook) has six self-contained Java recipes.
- **Building for production?** [Durable runs](recipes/durable-runs.md) shows how to survive a mid-workflow crash.
- **Worried about cost?** [Stop your agent burning $1000 overnight](tutorials/stop-your-agent-burning-money.md) walks through every governance gate.

---

!!! note "Scope"
    AgentFlow4J is an independent open-source project. It is **not** an official Spring project.
