---
description: "Run LLM agents in Java for free: create a Mistral account, grab a free API key, and wire it into an AgentFlow4J agent on Spring Boot — step by step, no credit card maze, no Python."
---

# Run your first Java AI agent for free (with Mistral)

Most "build an AI agent" tutorials assume Python and a paid OpenAI key. This one is **Java/Spring** and **free**: you'll create a [Mistral](https://mistral.ai/) account, get a free API key, and run a real LLM-backed agent with [AgentFlow4J](https://github.com/datallmhub/agentflow4j) — start to finish in about ten minutes.

Mistral offers a **free tier on `mistral-small`** — enough to build and test agents without spending anything.

---

## Step 1 — Create a Mistral account

1. Go to **<https://console.mistral.ai/>**
2. Sign up (email, Google, or GitHub).
3. You land on **La Plateforme**, Mistral's developer console.

## Step 2 — Get a free API key

1. In the console, open **API Keys** → <https://console.mistral.ai/api-keys/>
2. Click **Create new key**, give it a name (e.g. `agentflow4j-dev`), and copy it. It looks like `sk-...`.
3. Keep it somewhere safe — you only see it once.

!!! warning "Treat the key like a password"
    Never paste it into a commit, screenshot, or chat tool. AgentFlow4J reads it from an **environment variable**, never from a file in your repo. If a key leaks, rotate it in the console immediately.

Export it in your shell:

```bash
export MISTRAL_API_KEY="sk-...your-key..."
```

## Step 3 — Add AgentFlow4J + the Mistral starter

In a Spring Boot project, add the AgentFlow4J starter (via [JitPack](https://jitpack.io/#datallmhub/agentflow4j)) and Spring AI's Mistral starter:

=== "Maven"

    ```xml
    <repositories>
        <repository>
            <id>jitpack.io</id>
            <url>https://jitpack.io</url>
        </repository>
    </repositories>

    <!-- AgentFlow4J -->
    <dependency>
        <groupId>com.github.datallmhub.agentflow4j</groupId>
        <artifactId>agentflow4j-starter</artifactId>
        <version>v0.7.0</version>
    </dependency>

    <!-- Spring AI — Mistral -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-model-mistral-ai</artifactId>
    </dependency>
    ```

=== "Gradle"

    ```groovy
    repositories { maven { url 'https://jitpack.io' } }

    dependencies {
        implementation 'com.github.datallmhub.agentflow4j:agentflow4j-starter:v0.7.0'
        implementation 'org.springframework.ai:spring-ai-starter-model-mistral-ai'
    }
    ```

## Step 4 — Point Spring AI at Mistral

In `src/main/resources/application.yml`:

```yaml
spring:
  ai:
    mistralai:
      api-key: ${MISTRAL_API_KEY}      # resolved from your shell at startup
      chat:
        options:
          model: mistral-small-latest  # the free-tier model
          temperature: 0.3
```

Spring AI now auto-configures a `ChatClient` backed by Mistral. AgentFlow4J uses that `ChatClient` as the brain of any agent.

## Step 5 — Write and run an agent

```java
import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

@Bean
CommandLineRunner demo(ChatClient.Builder chatClientBuilder) {
    Agent assistant = ExecutorAgent.builder()
            .name("assistant")
            .chatClient(chatClientBuilder.build())
            .systemPrompt("You are a concise, helpful assistant. Answer in one sentence.")
            .build();

    return args -> {
        AgentResult result = assistant.execute(
                AgentContext.of("What is backpressure in reactive streams?"));
        System.out.println("\nMistral says: " + result.text() + "\n");
    };
}
```

Run your app (`mvn spring-boot:run`) with `MISTRAL_API_KEY` set, and you'll see a real answer from Mistral — your first governed-ready Java agent, for free.

## Where to go next

You now have a working LLM agent in Java. To turn it into a real multi-agent workflow:

- **[Two API levels](../two-api-levels.md)** — Squad vs Graph, and how to compose agents into a stateful flow.
- **[Cookbook](https://github.com/datallmhub/agentflow4j-cookbook)** — five runnable recipes (RAG, ticket triage, web research, Slack bot, batch processing).
- **[Switch providers](../llm-providers.md)** — move from Mistral to OpenAI, Anthropic, Gemini or local Ollama by changing one dependency.
- **[Stop your agent burning $1000 overnight](stop-your-agent-burning-money.md)** — once you're past the free tier, cap spend with budget, tool and approval gates.

---

!!! tip "Staying on the free tier"
    `mistral-small-latest` is covered by Mistral's free tier and is plenty for building and testing agents. When you move to heavier models or production traffic, AgentFlow4J's [budget policy](../resilience.md#6-budget-policy-cost-gate) caps what an agent can spend — so a runaway loop can't surprise you on the invoice.
