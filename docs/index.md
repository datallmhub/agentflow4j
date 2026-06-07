---
description: "Most agent frameworks help you build agents. AgentFlow4J helps you run them in production. Security, authorization, governance, resilience, and FinOps — built into every Java and Spring AI workflow, JVM-native."
---

# AgentFlow4J

**Most agent frameworks help you build agents. AgentFlow4J helps you run them in production.**

Security, authorization, governance, resilience, and FinOps — built into every workflow, JVM-native, Spring-powered. Your existing Spring Security roles, Spring Data stores, Micrometer metrics, and Boot actuator endpoints are already wired into your agent graph. No new runtime to deploy, no new infrastructure to audit.

[:material-rocket-launch: Get started](two-api-levels.md){ .md-button .md-button--primary }
[:material-github: GitHub repo](https://github.com/datallmhub/agentflow4j){ .md-button }
[:material-package: JitPack](https://jitpack.io/#datallmhub/agentflow4j){ .md-button }

---

## What you get

| Spring AI | AgentFlow4J runtime |
|---|---|
| Primitives (`ChatClient`, tools) | Structured execution (`AgentGraph`, `CoordinatorAgent`) |
| Manual orchestration glue | Graph-based execution & dynamic routing |
| No durable state | Typed shared state (`StateKey<T>`) + checkpoints |
| Retry logic in user code | Built-in retry & circuit-breaker policies |
| No resume | Interrupt & resume from the last valid checkpoint |
| Agents fully trusted | Governed execution — tool, state-write, budget and approval gates |

---

## Try it in 30 seconds

```bash
git clone https://github.com/datallmhub/agentflow4j.git
cd agentflow4j
mvn install -DskipTests -q
mvn -pl agentflow4j-samples exec:java
```

Runs `SupportTriageDemo` — a customer-support ticket flowing through a graph: `triage → specialist → policy gate → reply`. Falls back to deterministic stubs offline, or calls Mistral when `MISTRAL_API_KEY` is set.

---

## Where to next

- **First time?** The [Getting started](getting-started.md) guide takes you from `mvn` dependency to a running graph.
- **Want runnable examples?** The [Cookbook](https://github.com/datallmhub/agentflow4j-cookbook) has five self-contained Java recipes — RAG, ticket triage, web research, Slack bot, batch processing.
- **New here?** Start with [Two API levels](two-api-levels.md) — the Squad vs Graph distinction.
- **Worried about cost or unsafe actions?** Read the [governance trilogy](tool-policy.md): tool, state and approval gates.
- **Building for production?** [Durable runs](recipes/durable-runs.md) shows how to survive a mid-workflow crash.
- **Want the full story?** The [Stop your agent burning $1000 overnight](tutorials/stop-your-agent-burning-money.md) tutorial walks through every governance piece end-to-end.

---

!!! note "Scope"
    AgentFlow4J is an independent open-source project. It is **not** an official Spring project.
