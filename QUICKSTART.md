# 🚀 AgentFlow4J Quickstart

Get a multi-agent workflow running in 5 minutes — no API key required.

## Prerequisites

- **Java 17+** — [Download](https://adoptium.net/)
- **Maven 3.8+** — `mvn --version` to check
- **Git**

## 1. Clone & Build

```bash
git clone https://github.com/datallmhub/agentflow4j.git
cd agentflow4j
mvn install -DskipTests -q
```

## 2. Run your first agent workflow

```bash
mvn -pl agentflow4j-samples exec:java
```

This runs `SupportTriageDemo` — a customer-support ticket flowing through a multi-agent graph:

```
Customer → Triage Agent → Specialist Agent → Policy Gate → Reply
```

The demo uses **deterministic stubs** (no API key needed), so it works offline.

**Expected output:**

```
🚀 Customer Support AgentFlow4J Application Started!
📝 Streaming triage...
📝 Creating support ticket...
📝 Specialist agent analyzing...
✅ Ticket processed: ...
```

## 3. Try with a real LLM (optional)

```bash
export MISTRAL_API_KEY="your-key-here"
mvn -pl agentflow4j-samples exec:java
```

Now agents call Mistral for real reasoning instead of stubs.

## 4. Write your own agent

```java
ExecutorAgent myAgent = ExecutorAgent.builder()
    .chatClient(chatClient)
    .systemPrompt("You are a helpful assistant.")
    .build();

AgentResult result = myAgent.execute(
    AgentContext.of("Explain microservices in one sentence."));
```

## 5. Explore the cookbook

Ready for more? Check out the [cookbook](https://github.com/datallmhub/agentflow4j-cookbook) for ready-to-run examples:

- 🧠 RAG Agent
- 🎫 Support Ticket Triage
- 🔍 Web Research Agent
- 💬 Slack Bot
- 📄 Batch Document Processor

---

**Need help?** Open an [issue](https://github.com/datallmhub/agentflow4j/issues) or check the [docs](https://github.com/datallmhub/agentflow4j/tree/main/docs).
