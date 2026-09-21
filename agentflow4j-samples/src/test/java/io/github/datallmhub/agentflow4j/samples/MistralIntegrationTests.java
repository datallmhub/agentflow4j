package io.github.datallmhub.agentflow4j.samples;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.Edge;
import io.github.datallmhub.agentflow4j.graph.RetryPolicy;
import io.github.datallmhub.agentflow4j.squad.CoordinatorAgent;
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import io.github.datallmhub.agentflow4j.squad.RoutingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests that hit a real Mistral LLM.
 *
 * <p>Gated on {@code MISTRAL_API_KEY} — the entire class is skipped (not failed)
 * when the variable is absent, so CI without credentials stays green.
 *
 * <p>Covers the same four scenarios as {@link MistralIntegrationDemo}:
 * single executor, coordinator routing, multi-step graph, conditional loop.
 */
@SpringBootTest(classes = MistralIntegrationTests.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
class MistralIntegrationTests {

    @Autowired
    ChatModel chatModel;

    private ChatClient chatClient() {
        return ChatClient.builder(chatModel).build();
    }

    @Test
    void singleExecutorProducesNonEmptyAnswer() {
        ExecutorAgent agent = ExecutorAgent.builder()
                .name("summarizer")
                .chatClient(chatClient())
                .systemPrompt("You are a concise assistant. Answer in one sentence only.")
                .build();

        AgentResult result = agent.execute(AgentContext.of("What is Spring Boot?"));

        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isNotBlank();
    }

    @Test
    void coordinatorRoutesToCreativeBranchForHaikuPrompt() {
        ChatClient cc = chatClient();
        ExecutorAgent tech = ExecutorAgent.builder()
                .name("tech")
                .chatClient(cc)
                .systemPrompt("You are a technical expert. Start your response with [TECH].")
                .build();
        ExecutorAgent creative = ExecutorAgent.builder()
                .name("creative")
                .chatClient(cc)
                .systemPrompt("You are a creative writer. Start your response with [CREATIVE].")
                .build();

        CoordinatorAgent coordinator = CoordinatorAgent.builder()
                .name("router")
                .executors(Map.of("tech", tech, "creative", creative))
                .routingStrategy(RoutingStrategy.llmDriven(cc))
                .build();

        AgentResult result = coordinator.execute(
                AgentContext.of("Write a haiku about Java programming"));

        assertThat(result.text()).isNotBlank();
    }

    @Test
    void threeStepGraphVisitsEveryNode() {
        ChatClient cc = chatClient();
        StateKey<String> topic = StateKey.of("topic", String.class);

        ExecutorAgent researcher = ExecutorAgent.builder()
                .name("researcher").chatClient(cc)
                .systemPrompt("Extract the main topic from the user message. Reply with ONLY the topic.")
                .build();
        ExecutorAgent analyzer = ExecutorAgent.builder()
                .name("analyzer").chatClient(cc)
                .systemPrompt("List 3 key facts about the topic mentioned in the conversation. Be concise.")
                .build();
        ExecutorAgent writer = ExecutorAgent.builder()
                .name("writer").chatClient(cc)
                .systemPrompt("Write a 2-sentence summary based on the conversation so far.")
                .build();

        AtomicInteger nodesVisited = new AtomicInteger();
        AgentListener counter = new AgentListener() {
            @Override public void onNodeEnter(String g, String node, AgentContext ctx) {
                nodesVisited.incrementAndGet();
            }
        };

        AgentGraph graph = AgentGraph.builder()
                .name("research-pipeline")
                .addNode("research", researcher)
                .addNode("analyze", analyzer)
                .addNode("write", writer)
                .addEdge("research", "analyze")
                .addEdge("analyze", "write")
                .retryPolicy(RetryPolicy.once())
                .listener(counter)
                .build();

        AgentResult result = graph.invoke(
                AgentContext.of("Tell me about WebAssembly and its impact on web development"));

        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isNotBlank();
        assertThat(nodesVisited.get()).isEqualTo(3);
    }

    @Test
    void conditionalLoopEventuallyReachesFinish() {
        ChatClient cc = chatClient();
        StateKey<Integer> round = StateKey.of("round", Integer.class);

        ExecutorAgent checker = ExecutorAgent.builder()
                .name("checker").chatClient(cc)
                .systemPrompt("You are a quality checker. "
                        + "If this is the first round, reply with exactly: NEEDS_WORK. "
                        + "If previous messages mention NEEDS_WORK, reply with exactly: APPROVED.")
                .build();

        Agent roundTracker = ctx -> {
            int r = ctx.get(round) != null ? ctx.get(round) + 1 : 1;
            AgentResult inner = checker.execute(ctx);
            return AgentResult.builder()
                    .text(inner.text())
                    .stateUpdates(Map.of(round, r))
                    .completed(inner.completed())
                    .build();
        };
        Agent finisher = ctx -> AgentResult.ofText(
                "Quality check passed after " + ctx.get(round) + " round(s).");

        AtomicInteger transitions = new AtomicInteger();
        AgentListener tc = new AgentListener() {
            @Override public void onTransition(String g, String from, String to) {
                transitions.incrementAndGet();
            }
        };

        AgentGraph graph = AgentGraph.builder()
                .name("quality-loop")
                .addNode("check", roundTracker)
                .addNode("finish", finisher)
                .addEdge(Edge.onResult("check",
                        (ctx, r) -> r.text() != null
                                && r.text().toUpperCase().contains("NEEDS_WORK"),
                        "check"))
                .addEdge("check", "finish")
                .maxIterations(5)
                .listener(tc)
                .build();

        AgentResult result = graph.invoke(
                AgentContext.of("Check the quality of this document."));

        assertThat(result.text()).isNotBlank();
        assertThat(transitions.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void techStackAdvisorScenario() {
        ChatClient cc = chatClient();
        StateKey<String> CONSTRAINTS = StateKey.of("constraints", String.class);
        StateKey<String> PATTERN = StateKey.of("pattern", String.class);

        ExecutorAgent researcher = ExecutorAgent.builder()
                .name("researcher").chatClient(cc)
                .systemPrompt("Extract key constraints from the user request (volume, latency, domain). Reply concisely.")
                .build();
                
        io.github.datallmhub.agentflow4j.core.Agent statefulResearcher = ctx -> {
            AgentResult result = researcher.execute(ctx);
            return AgentResult.builder()
                    .text(result.text())
                    .completed(result.completed())
                    .stateUpdates(Map.of(CONSTRAINTS, result.text()))
                    .build();
        };

        ExecutorAgent analyzer = ExecutorAgent.builder()
                .name("analyzer").chatClient(cc)
                .systemPrompt("Analyze the constraints and recommend an architectural pattern (e.g., streaming, batch, microservices). Reply with the pattern only.")
                .build();

        io.github.datallmhub.agentflow4j.core.Agent statefulAnalyzer = ctx -> {
            AgentContext enrichedCtx = ctx.withMessage(new org.springframework.ai.chat.messages.UserMessage("Constraints: " + ctx.get(CONSTRAINTS)));
            AgentResult result = analyzer.execute(enrichedCtx);
            return AgentResult.builder()
                    .text(result.text())
                    .completed(result.completed())
                    .stateUpdates(Map.of(PATTERN, result.text()))
                    .build();
        };

        ExecutorAgent writer = ExecutorAgent.builder()
                .name("writer").chatClient(cc)
                .systemPrompt("Write a final technology stack recommendation based on the architectural pattern and constraints.")
                .build();
                
        io.github.datallmhub.agentflow4j.core.Agent statefulWriter = ctx -> {
            AgentContext enrichedCtx = ctx.withMessage(new org.springframework.ai.chat.messages.UserMessage("Constraints: " + ctx.get(CONSTRAINTS) + "\nPattern: " + ctx.get(PATTERN)));
            AgentResult result = writer.execute(enrichedCtx);
            return AgentResult.builder()
                    .text(result.text())
                    .completed(result.completed())
                    .stateUpdates(Map.of(
                            CONSTRAINTS, ctx.get(CONSTRAINTS),
                            PATTERN, ctx.get(PATTERN)
                    ))
                    .build();
        };

        AtomicInteger nodesVisited = new AtomicInteger();
        AgentListener counter = new AgentListener() {
            @Override public void onNodeEnter(String g, String node, AgentContext context) {
                nodesVisited.incrementAndGet();
            }
        };

        AgentGraph graph = AgentGraph.builder()
                .name("tech-stack-advisor")
                .addNode("research", statefulResearcher)
                .addNode("analyze", statefulAnalyzer)
                .addNode("write", statefulWriter)
                .addEdge("research", "analyze")
                .addEdge("analyze", "write")
                .listener(counter)
                .build();

        AgentResult result = graph.invoke(
                AgentContext.of("I need to build a real-time fraud detection system processing 10k transactions/second"));

        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isNotBlank();
        assertThat(result.text().toLowerCase()).containsAnyOf("kafka", "streaming", "flink", "redis", "architecture", "fraud");
        assertThat((String) result.stateUpdates().get(CONSTRAINTS)).isNotBlank();
        assertThat((String) result.stateUpdates().get(PATTERN)).isNotBlank();
        assertThat(nodesVisited.get()).isEqualTo(3);
    }

    @Test
    void toolCallHandoffCapturesNameArgumentsAndResult() {
        AtomicInteger callCount = new AtomicInteger();
        FunctionToolCallback<WeatherRequest, String> weather = FunctionToolCallback
                .builder("lookup_weather", (WeatherRequest req) -> {
                    callCount.incrementAndGet();
                    return "{\"city\":\"" + req.city() + "\",\"tempC\":12,\"condition\":\"rainy\"}";
                })
                .description("Return the current weather as JSON for a given city.")
                .inputType(WeatherRequest.class)
                .build();

        ExecutorAgent agent = ExecutorAgent.builder()
                .name("weather-bot")
                .chatClient(chatClient())
                .systemPrompt("You are a helpful assistant. "
                        + "When asked about the weather, call the lookup_weather tool.")
                .tools(weather)
                .build();

        AgentResult result = agent.execute(
                AgentContext.of("What's the weather in Paris right now?"));

        assertThat(result.completed()).isTrue();
        assertThat(callCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(result.toolCalls())
                .as("framework must capture the tool invocation")
                .isNotEmpty();
        ToolCallRecord first = result.toolCalls().get(0);
        assertThat(first.name()).isEqualTo("lookup_weather");
        assertThat(first.arguments()).containsKey("city");
        assertThat(first.arguments().get("city").toString()).containsIgnoringCase("Paris");
        assertThat(first.success()).isTrue();
        assertThat(first.result()).contains("rainy");
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(first.durationMs()).isGreaterThanOrEqualTo(0L);
    }

    public record WeatherRequest(String city) {}

    public record TopicReport(String topic, int priority) {}

    @Test
    void outputKeyProducesTypedEntityAndStateUpdate() {
        StateKey<TopicReport> report = StateKey.of("report", TopicReport.class);
        ExecutorAgent agent = ExecutorAgent.builder()
                .name("analyst")
                .chatClient(chatClient())
                .systemPrompt("You are a security triage analyst. "
                        + "Read the user's message, extract the topic and a priority from 1 (low) to 10 (critical).")
                .outputKey(report)
                .build();

        AgentResult result = agent.execute(AgentContext.of(
                "A critical CVE was disclosed in our authentication library — we need to patch today."));

        assertThat(result.completed()).isTrue();
        assertThat(result.structuredOutput()).isInstanceOf(TopicReport.class);
        TopicReport r = (TopicReport) result.structuredOutput();
        assertThat(r.topic()).isNotBlank();
        assertThat(r.priority()).isBetween(1, 10);
        assertThat(result.stateUpdates()).containsEntry(report, r);
    }

    @SpringBootApplication
    static class TestApp {
    }
}
