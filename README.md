# AgentFlow4J

**Build AI agents you can trust in production.**

AgentFlow4J is a framework and runtime for governed, stateful multi-agent systems on the JVM.

Human approvals · Checkpoints · Budget controls · Tool policies · Durable execution

---

## Why AgentFlow4J?

- Build multi-agent workflows with explicit orchestration
- Persist execution across failures and restarts
- Add human approval where it matters
- Control tool access and AI spend
- Run natively on the JVM and Spring ecosystem

---

<p align="center">
<img width="1536" height="768" alt="AgentFlow4J: Build · Govern · Run" src="docs/images/hero.jpg" />
</p>

[![build](https://github.com/datallmhub/agentflow4j/actions/workflows/build.yml/badge.svg)](https://github.com/datallmhub/agentflow4j/actions)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue)](https://adoptium.net/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0-green)](https://docs.spring.io/spring-ai/reference/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## 🚀 Try it in 5 minutes (no API key)

```bash
git clone https://github.com/datallmhub/agentflow4j.git
cd agentflow4j
mvn install -DskipTests -q
mvn -pl agentflow4j-samples exec:java
```

Runs `SupportTriageDemo` by default: a **business example built with AgentFlow4J**: a support ticket flowing through a governed graph (triage → specialist → policy gate → reply), with `ToolPolicy` and `ApprovalGate` active. This is one sample use case to illustrate the framework, not the framework itself. No API key required; falls back to deterministic stubs, or calls Mistral when `MISTRAL_API_KEY` is set.

Other demos to explore:

| Demo | What it shows |
|---|---|
| `SupportTriageDemo` | Multi-agent graph with `ToolPolicy` + `ApprovalGate` |
| `BudgetAwareRoutingDemo` | `BudgetPolicy` switching agents at runtime |
| `ResearchSquad` | `ParallelAgent` fan-out + result aggregation |
| `AdvancedGraphDemo` | Loops, conditions, typed state |
| `MinimalPipeline` | Smallest possible `AgentGraph`: two nodes, one edge |

```bash
# Run any demo directly
mvn -pl agentflow4j-samples exec:java -Dexec.mainClass=io.github.datallmhub.agentflow4j.samples.BudgetAwareRoutingDemo
```

---

## ⚡ In 60 seconds

```java
// Define your agents
ExecutorAgent analyst = ExecutorAgent.builder()
    .chatClient(chatClient)
    .systemPrompt("Analyse this request.")
    .toolPolicy(ToolPolicy.allowList("search", "fetch"))
    .build();

// Compose a governed graph
AgentGraph graph = AgentGraph.builder()
    .addNode("analyse", analyst)
    .budgetPolicy(BudgetPolicy.perRun(0.50, estimator, meter))
    .approvalGate(ApprovalGate.requireFor("analyse"))
    .checkpointStore(new JdbcCheckpointStore(dataSource))
    .build();

AgentResult result = graph.run(AgentContext.of("Process this refund request"));
```

`ToolPolicy` restricts which tools the agent can call. `BudgetPolicy` caps spend at $0.50 per run. `ApprovalGate` pauses execution until a human approves. `CheckpointStore` persists graph state: the run resumes from the last completed node after a restart.

⭐ **If this saves you time, consider [starring the repo](https://github.com/datallmhub/agentflow4j).**

---

## 🎬 Example in action

One real workflow built with AgentFlow4J: a customer-support triage app. This is **one sample use case**, not the framework itself; you compose your own agents and graphs the same way.

<p align="center">
<img width="760" alt="A customer-support multi-agent workflow built with AgentFlow4J, running live" src="docs/images/use-case.gif" />
</p>

▶ Live demo: <https://huggingface.co/spaces/datallmhub/multi-agent-customer-ops>

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

Agents are **not implicitly trusted**. Gate what they can call, what they can change, what they can spend, and when a human must step in:

```java
AgentGraph.builder()
    .addNode("assistant", assistant)
    .addNode("payment.transfer", paymentAgent)
    .toolPolicy(ToolPolicy.allowList("web.search").and(ToolPolicy.denyList("shell.execute")))
    .statePolicy(StatePolicy.denyWriteKeys("payment.confirmed"))
    .budgetPolicy(BudgetPolicy.hierarchical(BudgetLimits.run(2.00), estimator, meter))
    .approvalGate(ApprovalGate.requireFor("payment.transfer"))
    .checkpointStore(store)
    .build();
```

Two API levels: **Squad API** for dynamic routing with minimal setup, **Graph API** for explicit flows, loops and full control. See [Two API levels](docs/two-api-levels.md).

---

## Why not Spring AI alone?

Spring AI gives you the primitives to call a model. AgentFlow4J gives you the runtime to orchestrate agents safely.

| Spring AI | AgentFlow4J |
|---|---|
| Talk to AI models | Coordinate multiple agents |
| Prompts & tools | Agent teams with routing |
| Tool calling | Governance: who can call what |
| `ChatClient` | `AgentGraph` execution |
| RAG | Checkpointing & resume |
| Model providers | Retry, resilience, audit trail |

**Spring AI provides AI capabilities. AgentFlow4J provides agent execution.**

LangGraph, ADK, CrewAI and AutoGen bring their own runtimes. AgentFlow4J runs on the Spring stack your team already owns, already secures, and already operates.

**Use it if** your workflow spans multiple agents, failures matter, costs need capping, or a human must approve before an action executes.
**Skip it if** you make a single `ChatClient` call.

---

## 🛠 Installation

**Requirements:** Java 17+, Spring Boot 3.x, Spring AI 1.0+.
Distributed via [JitPack](https://jitpack.io).

### Maven

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependency>
    <groupId>com.github.datallmhub.agentflow4j</groupId>
    <artifactId>agentflow4j-starter</artifactId>
    <version>v0.7.0</version>
</dependency>
```

### Gradle

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.datallmhub.agentflow4j:agentflow4j-starter:v0.7.0' }
```

### Modules

| Module | Purpose |
|---|---|
| `agentflow4j-starter` | Spring Boot auto-config, properties, Micrometer listener |
| `agentflow4j-core` | Minimal API (`Agent`, `AgentContext`, `StateKey`, `AgentResult`) |
| `agentflow4j-graph` | `AgentGraph`, `RetryPolicy`, `CircuitBreakerPolicy`, `BudgetPolicy`, checkpoint contract |
| `agentflow4j-squad` | `CoordinatorAgent`, `ExecutorAgent`, `ReActAgent`, `ParallelAgent` |
| `agentflow4j-checkpoint` | `JdbcCheckpointStore`, `RedisCheckpointStore`, Jackson codec |
| `agentflow4j-resilience4j` | `CircuitBreakerPolicy` adapter backed by Resilience4j |
| `agentflow4j-playground` | Drop-in web UI to chat with your `Agent` beans |
| `agentflow4j-cli-agents` | `CliAgentNode`: Claude Code / Codex / Gemini CLI as graph nodes |
| `agentflow4j-test` | `MockAgent`, `TestGraph` for LLM-free unit tests |

---

## 📚 Documentation

- [Two API levels (Squad + Graph)](docs/two-api-levels.md): when to use which, with code
- [LLM providers](docs/llm-providers.md): swap between Mistral, OpenAI, Claude, Gemini, Ollama with two lines of config
- [Typed state](docs/state.md): `StateKey<T>` instead of `Map<String, Object>`
- [Tool policy](docs/tool-policy.md): allow/deny tool calls per agent, with argument-aware rules
- [State policy](docs/state-policy.md): allow/deny writes to specific `StateKey<T>`, with argument-aware rules
- [Approval gate](docs/approval-gate.md): human-in-the-loop pause/resume on sensitive nodes
  - [Recipe: approval via Slack](docs/recipes/approval-via-slack.md): async, non-blocking, ~30 lines
- [Resilience & error handling](docs/resilience.md): retries, circuit breaker, budget policy
  - [Recipe: durable runs](docs/recipes/durable-runs.md): crash mid-workflow, resume from the last checkpoint
- [Observability](docs/observability.md): Micrometer metrics, tags, listeners
- [Run log](docs/run-log.md): structured, replayable execution timeline per run
- [Streaming](docs/streaming.md): `Flux<AgentEvent>` tokens, transitions, tool calls
- [Testing without an LLM](docs/testing.md): `MockAgent` + `TestGraph`
- [Samples](docs/samples.md): runnable examples shipped with the repo

**Cookbook:** [AgentFlow4J Cookbook](https://github.com/datallmhub/agentflow4j-cookbook): standalone, copy-paste recipes (RAG agent, support-ticket triage, web research, Slack bot, batch document processing), each a self-contained Maven module that runs locally against Ollama.

**Tutorial:** [Stop your AI agent from burning $1000 overnight](docs/tutorials/stop-your-agent-burning-money.md): governed execution end to end.

---

## 📈 Roadmap

| Version | Status | Focus |
|---------|--------|-------|
| **0.5** | shipped | Subgraphs, parallel fan-out, cancellation, typed output, retry/circuit-breaker/budget policies, JDBC/Redis checkpoint store, web playground |
| **0.6** | shipped | Governed execution: `ToolPolicy`, `StatePolicy`, `ApprovalGate`: allow/deny tools, guard state writes, human-in-the-loop pause/resume |
| **0.7** | shipped | Adaptive execution: reason-aware retry (`FailureClassifier`), cost-aware routing (`BudgetAwareRouter`) |
| **1.0** | in progress | API stabilization pass · lifecycle hooks (`onCheckpoint`, `onToolCall`, `onFailure`) · MCP compatibility · OpenHands integration adapter · Parallel-DAG execution |
| **2.0** | exploring | Temporal-like interruption, compensation/saga, OpenTelemetry tracing |

---

## 🤝 Contributing & License

Contributions welcome: see [CONTRIBUTING.md](CONTRIBUTING.md).
Released under the [Apache 2.0 License](LICENSE). Not an official Spring project.
