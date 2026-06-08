---
description: "Step-by-step tutorial: build a multi-agent workflow in Java with Spring AI and AgentFlow4J. Agents, routing, typed state, governance gates — end to end."
---

# How to build a multi-agent workflow in Java with Spring AI

Most Spring AI tutorials stop at a single `ChatClient` call. That's fine for a chatbot, but real production systems need more: multiple agents collaborating, durable state surviving restarts, routing decisions based on context, and governance to prevent runaway costs or unsafe actions.

This tutorial builds a **document review workflow** from scratch — three agents, a governed graph, typed state, and a human approval gate — using [AgentFlow4J](https://github.com/datallmhub/agentflow4j) on top of Spring AI.

---

## What we are building

A three-agent pipeline that reviews a legal document:

```
User submits document
        ↓
  [classifier]  — determines document type and risk level
        ↓
  [analyst]     — extracts key clauses and flags issues
        ↓
  ApprovalGate  — pauses if risk is HIGH, waits for human sign-off
        ↓
  [summariser]  — produces the final review report
        ↓
  AgentResult   — returned to the caller + persisted in RunLog
```

---

## Setup

**Requirements:** Java 17+, Spring Boot 3.x, Spring AI 1.0+.

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

<!-- Spring AI provider — swap for OpenAI, Anthropic, Gemini, Ollama -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-mistral-ai</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  ai:
    mistralai:
      api-key: ${MISTRAL_API_KEY}
      chat:
        options:
          model: mistral-small-latest
```

---

## Step 1 — Define typed state

Agents share data through a typed `AgentContext`. Instead of a raw `Map<String, Object>`, declare `StateKey<T>` constants — the compiler catches key mismatches.

```java
import io.github.datallmhub.agentflow4j.core.StateKey;

public final class ReviewKeys {
    public static final StateKey<String>  DOCUMENT      = StateKey.of("document",     String.class);
    public static final StateKey<String>  DOC_TYPE      = StateKey.of("doc.type",     String.class);
    public static final StateKey<String>  RISK_LEVEL    = StateKey.of("risk.level",   String.class);
    public static final StateKey<String>  ISSUES        = StateKey.of("issues",       String.class);
    public static final StateKey<String>  FINAL_REPORT  = StateKey.of("final.report", String.class);

    private ReviewKeys() {}
}
```

---

## Step 2 — Write the three agents

Each agent is a Spring bean backed by an `ExecutorAgent`. The system prompt defines its role; the agent reads its inputs from `AgentContext` and writes its output back.

```java
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import io.github.datallmhub.agentflow4j.core.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ReviewAgents {

    @Bean
    Agent classifier(ChatClient.Builder builder) {
        return ExecutorAgent.builder()
            .chatClient(builder.build())
            .systemPrompt("""
                You are a legal document classifier.
                Read the document and return JSON with two fields:
                  "type": one of [CONTRACT, NDA, INVOICE, OTHER]
                  "risk": one of [LOW, MEDIUM, HIGH]
                """)
            .inputMapper(ctx -> ctx.get(ReviewKeys.DOCUMENT))
            .outputMapper((ctx, result) -> ctx
                .with(ReviewKeys.DOC_TYPE,   extractField(result.text(), "type"))
                .with(ReviewKeys.RISK_LEVEL, extractField(result.text(), "risk")))
            .build();
    }

    @Bean
    Agent analyst(ChatClient.Builder builder) {
        return ExecutorAgent.builder()
            .chatClient(builder.build())
            .systemPrompt("""
                You are a legal analyst.
                Extract the three most important clauses and flag any issues.
                Be concise — bullet points only.
                """)
            .inputMapper(ctx -> ctx.get(ReviewKeys.DOCUMENT))
            .outputMapper((ctx, result) -> ctx.with(ReviewKeys.ISSUES, result.text()))
            .build();
    }

    @Bean
    Agent summariser(ChatClient.Builder builder) {
        return ExecutorAgent.builder()
            .chatClient(builder.build())
            .systemPrompt("""
                You are a senior legal reviewer.
                Produce a one-page review report from the document type,
                risk level, and analyst findings provided.
                """)
            .inputMapper(ctx -> String.format(
                "Type: %s\nRisk: %s\nFindings:\n%s",
                ctx.get(ReviewKeys.DOC_TYPE),
                ctx.get(ReviewKeys.RISK_LEVEL),
                ctx.get(ReviewKeys.ISSUES)))
            .outputMapper((ctx, result) -> ctx.with(ReviewKeys.FINAL_REPORT, result.text()))
            .build();
    }

    private String extractField(String json, String field) {
        // minimal JSON extraction — replace with Jackson in production
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) return "UNKNOWN";
        int colon = json.indexOf(":", i);
        int start = json.indexOf("\"", colon) + 1;
        int end   = json.indexOf("\"", start);
        return json.substring(start, end);
    }
}
```

---

## Step 3 — Compose the governed graph

The graph wires the agents together, adds an approval gate before the summariser for high-risk documents, and caps total spend at $0.20 per run.

```java
import io.github.datallmhub.agentflow4j.graph.*;
import io.github.datallmhub.agentflow4j.checkpoint.JdbcCheckpointStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

@Configuration
public class ReviewGraph {

    @Bean
    AgentGraph documentReviewGraph(
            Agent classifier,
            Agent analyst,
            Agent summariser,
            DataSource dataSource,
            CostEstimator estimator,
            CostMeter meter) {

        return AgentGraph.builder()
            .name("document-review")
            .addNode("classify",  classifier)
            .addNode("analyse",   analyst)
            .addNode("summarise", summariser)
            .addEdge("classify",  "analyse")
            .addEdge("analyse",   "summarise")
            // pause before summarise if risk is HIGH
            .approvalGate(ApprovalGate.when(
                (node, ctx) -> "summarise".equals(node)
                    && "HIGH".equals(ctx.get(ReviewKeys.RISK_LEVEL)),
                "High-risk document requires senior sign-off"))
            // cap total spend per run
            .budgetPolicy(BudgetPolicy.hierarchical(
                BudgetLimits.run(0.20), estimator, meter))
            // persist state after every node — resume after restart
            .checkpointStore(new JdbcCheckpointStore(dataSource))
            .build();
    }
}
```

---

## Step 4 — Run the workflow

```java
import io.github.datallmhub.agentflow4j.core.*;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import org.springframework.stereotype.Service;

@Service
public class DocumentReviewService {

    private final AgentGraph graph;

    public DocumentReviewService(AgentGraph documentReviewGraph) {
        this.graph = documentReviewGraph;
    }

    public String review(String documentText) {
        AgentContext ctx = AgentContext.builder()
            .with(ReviewKeys.DOCUMENT, documentText)
            .build();

        AgentResult result = graph.invoke(ctx);

        if (result.isInterrupted()) {
            // ApprovalGate fired — notify a human and store the run ID
            return "Pending review. Run ID: " + result.runId();
        }

        return result.context().get(ReviewKeys.FINAL_REPORT);
    }

    public String approve(String runId) {
        AgentResult result = graph.resumeWithApproval(runId, "senior-reviewer");
        return result.context().get(ReviewKeys.FINAL_REPORT);
    }
}
```

---

## Step 5 — Handle the approval gate

When the `ApprovalGate` fires, the graph pauses, persists a checkpoint, and returns an interrupted result. Resume it once a human approves:

```java
// 1. Submit a high-risk document
String outcome = service.review(highRiskNda);
// → "Pending review. Run ID: run_abc123"

// 2. A senior reviewer approves (could be triggered by a Slack button, webhook, etc.)
String report = service.approve("run_abc123");
// → The graph resumes from the checkpoint, skips classify + analyse (already done),
//   runs summarise, and returns the final report.
```

The checkpoint guarantees that `classify` and `analyse` do not re-run — even if the server restarted between submission and approval.

---

## What you get out of the box

Running this workflow gives you:

- **Structured execution trace** — every node transition, timing, and cost in `RunLog`
- **Micrometer metrics** — `agentflow4j.node.duration`, `agentflow4j.run.cost` on your existing Actuator endpoint
- **Automatic retry** — transient LLM failures are retried with exponential backoff before surfacing as errors
- **Budget enforcement** — if the three agents together exceed $0.20, the run aborts with `BudgetExceededException`

---

## Next steps

- **[Cookbook recipe 02](https://github.com/datallmhub/agentflow4j-cookbook/tree/main/02-support-ticket-triage)** — a similar three-agent graph with `ToolPolicy` and a Slack approval hook
- **[Resilience & retry](../resilience.md)** — configure `RetryPolicy` + `FailureClassifier` for cost-aware retries
- **[Approval via Slack](../recipes/approval-via-slack.md)** — async, non-blocking human approval in ~30 lines
- **[Observability](../observability.md)** — what Micrometer metrics the runtime emits and how to query them
