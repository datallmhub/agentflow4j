---
description: "Swap LLM provider in AF4J with two lines of config: Mistral, OpenAI (GPT), Anthropic Claude, Google Vertex Gemini or a local Ollama. Spring AI handles the auto-configuration; agent code stays unchanged."
---

# LLM providers — swap freely

AF4J builds on top of Spring AI. Anything Spring AI auto-configures as a `ChatModel` works as the brain of an `ExecutorAgent` — Mistral, OpenAI, Anthropic Claude, Google Gemini, a local Ollama, your own. Switching is just **two changes**: the starter dependency, and the API key property.

The playground's `llm` bean is provider-agnostic. It receives whatever `ChatModel` Spring AI built; you decide which one.

## Getting an API key

Every paid provider follows the same flow: sign in to their console, create a key, export it as an environment variable. AF4J **never reads the key from a file** — it stays in your shell, out of the repo.

| Provider | Console URL | Environment variable |
|---|---|---|
| **Mistral** | <https://console.mistral.ai/api-keys/> | `export MISTRAL_API_KEY="sk-..."` |
| **OpenAI** | <https://platform.openai.com/api-keys> | `export OPENAI_API_KEY="sk-..."` |
| **Anthropic** | <https://console.anthropic.com/settings/keys> | `export ANTHROPIC_API_KEY="sk-ant-..."` |
| **Google Vertex** | <https://console.cloud.google.com/> | `gcloud auth application-default login` (no key, uses ADC) |
| **Ollama** (local) | — | no key needed — run `ollama serve` locally |

A few practical notes:

- **Mistral** offers a generous free tier on `mistral-small` — enough to try the playground without spending anything.
- **OpenAI / Anthropic** require billing setup before keys work; both have minimal-credit plans for experimentation.
- Treat the key like a password. **Don't paste it into chat tools, screenshots or commit messages.** If you ever expose one, rotate it in the provider's console immediately.
- The samples ship with `api-key: ${MISTRAL_API_KEY}` — Spring resolves the placeholder from your shell at startup. The key is never persisted in the repo.

Once exported, run the playground in the same shell:

```bash
export MISTRAL_API_KEY="your-key"
mvn -pl agentflow4j-samples -am exec:java \
  -Dexec.mainClass=io.github.datallmhub.agentflow4j.samples.playground.PlaygroundDemo
```

The `llm` agent will appear in the playground dropdown, backed by the real model.

## The swap recipe

For each provider, drop in the matching dependency and set the matching property. Pick one — putting multiple model starters on the same classpath is supported, but Spring AI will then need an explicit selector. For a sample, one provider at a time is the cleanest setup.

### Mistral (default in the playground)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-mistral-ai</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    mistralai:
      api-key: ${MISTRAL_API_KEY}
      chat:
        options:
          model: mistral-small-latest
          temperature: 0.3
```

### OpenAI (GPT family)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.3
```

### Anthropic (Claude)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-3-5-sonnet-latest
          temperature: 0.3
```

### Google (Vertex AI Gemini)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-vertex-ai-gemini</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    vertex:
      ai:
        gemini:
          project-id: ${GOOGLE_PROJECT_ID}
          location: us-central1
          chat:
            options:
              model: gemini-1.5-flash
```

Authentication uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials) — `gcloud auth application-default login` for local, or a service account in production.

### Ollama (local, no key)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.1
```

Run `ollama serve` locally and `ollama pull llama3.1` first. No key, no rate limit, fully on your machine — ideal for governance-sensitive workflows.

## Nothing else changes

Once the `ChatModel` bean is in the context, every part of AF4J keeps working unchanged:

- `ExecutorAgent.builder().chatClient(ChatClient.builder(chatModel).build())` — same code, any provider
- `CoordinatorAgent` routing via `RoutingStrategy.llmDriven(chatClient)` — same code
- `BudgetPolicy`, `ToolPolicy`, `StatePolicy`, `ApprovalGate` — provider-independent
- The run log, checkpoints, retries, circuit breaker — provider-independent

This is the point of building on Spring AI: the provider is a swap, not a rewrite.

## Multi-provider in one app

You can mix providers in the same graph — e.g., a cheap model for triage, a strong model for synthesis. Inject two distinct `ChatModel` beans (use Spring's `@Qualifier`) and pass each to the relevant `ExecutorAgent`. Spring AI's docs cover the multi-bean setup; from AF4J's perspective each agent simply takes a `ChatClient`.
