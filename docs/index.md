---
description: "AgentFlow4J helps you create, coordinate and govern AI agents working together on business tasks. Built for Java with approvals, budgets, permissions, checkpoints and production-grade execution."
---

# AgentFlow4J

**AgentFlow4J helps you create, coordinate and govern AI agents working together on business tasks.**

Built for Java — with approvals, budgets, permissions, checkpoints and production-grade execution.

[:material-rocket-launch: Get started](getting-started.md){ .md-button .md-button--primary }
[:material-book-open: Core concepts](concepts.md){ .md-button }
[:material-github: GitHub](https://github.com/datallmhub/agentflow4j){ .md-button }

---

## Core concepts

AgentFlow4J is built around four ideas:

| Concept | What it means | AgentFlow4J |
|---|---|---|
| **Agents** | Autonomous units that perform tasks | `ExecutorAgent`, `ReActAgent`, `ParallelAgent` |
| **Teams** | Agents working together, coordinated | `CoordinatorAgent`, `AgentGraph` |
| **Rules** | What agents can do, spend, or change | `ApprovalGate`, `BudgetPolicy`, `ToolPolicy`, `StatePolicy` |
| **Execution** | How runs survive failures and restarts | `RetryPolicy`, `CheckpointStore`, `RunLog`, Micrometer |

---

## Why not Spring AI alone?

Spring AI gives you the primitives to call a model. AgentFlow4J gives you the runtime to orchestrate agents safely.

| Spring AI | AgentFlow4J |
|---|---|
| Talk to AI models | Coordinate multiple agents |
| Prompts & tools | Agent teams with routing |
| Tool calling | Governance — who can call what |
| `ChatClient` | `AgentGraph` execution |
| RAG | Checkpointing & resume |
| Model providers | Retry, resilience, audit trail |

**Spring AI provides AI capabilities. AgentFlow4J provides agent execution.**

---

## Try it in 5 minutes

```bash
git clone https://github.com/datallmhub/agentflow4j.git
cd agentflow4j
mvn install -DskipTests -q
mvn -pl agentflow4j-samples exec:java
```

Runs `SupportTriageDemo` — a **business example built with AgentFlow4J**: a support ticket flowing through a governed graph with `ToolPolicy` and `ApprovalGate` active. No API key required.

---

## Where to next

- **New here?** Start with [Core concepts](concepts.md) — what agents, teams, rules and execution mean in AgentFlow4J.
- **Ready to code?** Follow the [Getting started](getting-started.md) guide — from `mvn` dependency to a running graph.
- **Want runnable examples?** The [Cookbook](https://github.com/datallmhub/agentflow4j-cookbook) has six self-contained Java recipes.
- **Building for production?** [Durable runs](recipes/durable-runs.md) shows how to survive a mid-workflow crash.
- **Worried about cost?** [Stop your agent burning $1000 overnight](tutorials/stop-your-agent-burning-money.md) walks through every governance gate.

---

!!! note "Scope"
    AgentFlow4J is an independent open-source project. It is **not** an official Spring project.
