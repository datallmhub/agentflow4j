---
description: "Get started with AgentFlow4J: add the dependency to your Spring app, write your first agent, then compose a multi-agent graph. Java 17+, Spring Boot 3.x, Spring AI."
---

# Getting started

This page takes you from an empty Spring project to a running multi-agent graph. Three steps: add the dependency, write a first agent, then compose a graph.

> Just want to see it run without integrating anything? Clone the repo and run the bundled demo — see [Try it in 30 seconds](index.md#try-it-in-30-seconds).

## Requirements

- **Java 17+**
- **Spring Boot 3.x** (the project is built and tested on 3.5.x)
- **Spring AI 1.0+** — AgentFlow4J builds on Spring AI's `ChatClient` and provider starters

## 1. Add the dependency

AgentFlow4J is distributed via [JitPack](https://jitpack.io/#datallmhub/agentflow4j). Add the repository and the starter.

=== "Maven"

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

=== "Gradle"

    ```groovy
    repositories { maven { url 'https://jitpack.io' } }

    dependencies {
        implementation 'com.github.datallmhub.agentflow4j:agentflow4j-starter:v0.7.0'
    }
    ```

The `agentflow4j-starter` pulls in the core, graph and squad modules plus the Spring Boot auto-configuration.

## 2. Your first agent (no LLM required)

An `Agent` is a single functional method: `AgentResult execute(AgentContext)`. The smallest possible agent is a lambda — runs instantly, no API key, ideal for wiring tests:

```java
import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;

// An Agent reads the context and returns a result.
Agent greeter = ctx -> {
    String user = ctx.messages().get(ctx.messages().size() - 1).getText();
    return AgentResult.ofText("Hello! You said: " + user);
};

AgentResult result = greeter.execute(AgentContext.of("hi there"));
System.out.println(result.text());   // Hello! You said: hi there
```

That's the whole contract every node in a graph implements. Everything else (LLM calls, routing, governance) builds on it.

## 3. Add an LLM

To back an agent with a real model, add a [Spring AI provider starter](llm-providers.md) and export its API key. Mistral has a free tier:

=== "Maven"

    ```xml
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
      api-key: ${MISTRAL_API_KEY}   # resolved from your shell, never committed
      chat:
        options:
          model: mistral-small-latest
```

Spring AI auto-configures a `ChatClient`. Wrap it in an `ExecutorAgent`:

```java
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import org.springframework.ai.chat.client.ChatClient;

@Bean
Agent assistant(ChatClient.Builder chatClientBuilder) {
    return ExecutorAgent.builder()
            .name("assistant")
            .chatClient(chatClientBuilder.build())
            .systemPrompt("You are a concise, helpful assistant.")
            .build();
}
```

Call it the same way:

```java
AgentResult answer = assistant.execute(
        AgentContext.of("Explain backpressure in one sentence."));
```

See [LLM providers](llm-providers.md) to switch to OpenAI, Anthropic, Gemini or local Ollama — it's a one-dependency change, the agent code stays the same.

## 4. Compose a graph

A single agent is rarely enough. An `AgentGraph` wires agents into a stateful flow with explicit edges:

```java
import io.github.datallmhub.agentflow4j.graph.AgentGraph;

Agent classify = ctx -> AgentResult.ofText("billing");
Agent reply    = ctx -> AgentResult.ofText("Routing your billing question to finance.");

AgentGraph graph = AgentGraph.builder()
        .name("support")
        .addNode("classify", classify)
        .addNode("reply", reply)
        .addEdge("classify", "reply")
        .build();

AgentResult result = graph.invoke(AgentContext.of("My invoice looks wrong"));
```

From here you can add typed state passed between nodes, conditional edges, retries, and governance gates — without writing orchestration glue.

## Next steps

- **[Cookbook](https://github.com/datallmhub/agentflow4j-cookbook)** — five runnable Java recipes (RAG, ticket triage, web research, Slack bot, batch processing), each a self-contained Maven module.
- **[Two API levels](two-api-levels.md)** — when to use the high-level Squad API vs the low-level Graph API.
- **[Typed state](state.md)** — share data between nodes with `StateKey<T>` instead of `Map<String, Object>`.
- **[Governance](tool-policy.md)** — cap spend, restrict tools, protect state, and require human approval.
- **[Resilience](resilience.md)** — retries, circuit breakers, and budget-aware cost gates.
