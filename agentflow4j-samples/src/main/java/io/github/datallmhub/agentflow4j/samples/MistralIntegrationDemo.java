package io.github.datallmhub.agentflow4j.samples;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.Edge;
import io.github.datallmhub.agentflow4j.graph.RetryPolicy;
import io.github.datallmhub.agentflow4j.squad.CoordinatorAgent;
import io.github.datallmhub.agentflow4j.squad.ExecutorAgent;
import io.github.datallmhub.agentflow4j.squad.RoutingStrategy;
import org.springframework.ai.chat.messages.UserMessage;
import io.github.datallmhub.agentflow4j.core.Agent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.api.MistralAiApi;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end integration test with a real LLM (Mistral AI).
 *
 * <p>Run with:
 * <pre>
 * MISTRAL_API_KEY=your-key mvn -pl agentflow4j-samples \
 *     spring-boot:run -Dspring-boot.run.mainClass=...MistralIntegrationDemo
 * </pre>
 *
 * <p>This validates 4 critical paths:
 * <ol>
 *   <li>ExecutorAgent → ChatClient → real LLM response</li>
 *   <li>CoordinatorAgent → routing to the correct executor</li>
 *   <li>AgentGraph → multi-step pipeline with state propagation</li>
 *   <li>AgentGraph → conditional routing based on LLM output</li>
 * </ol>
 */
@SpringBootApplication
public class MistralIntegrationDemo implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MistralIntegrationDemo.class);

    private final ChatClient chatClient;

    public MistralIntegrationDemo(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    public static void main(String[] args) {
        String apiKey = System.getenv("MISTRAL_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.error("\n❌ ERROR: MISTRAL_API_KEY environment variable is not set.");
            log.error("Please export your Mistral API key before running this demo:\n");
            log.error("export MISTRAL_API_KEY=\"your_key_here\"");
            log.error("mvn -pl agentflow4j-samples exec:java -Dexec.mainClass=\"io.github.datallmhub.agentflow4j.samples.MistralIntegrationDemo\"\n");
            System.exit(1);
        }

        SpringApplication app = new SpringApplication(MistralIntegrationDemo.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        log.info("suite.start: suite={}", "MistralIntegrationTests");

        boolean allPassed = true;
        allPassed &= test1_singleExecutor();
        allPassed &= test2_coordinatorRouting();
        allPassed &= test3_graphPipeline();
        allPassed &= test4_conditionalRouting();

        if (allPassed) {
            log.info("suite.success: suite={} completed=true", "MistralIntegrationTests");
        } else {
            log.error("suite.failure: suite={} completed=false", "MistralIntegrationTests");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 1: Single ExecutorAgent calls Mistral, gets a response
    // ─────────────────────────────────────────────────────────────
    private boolean test1_singleExecutor() {
        log.info("test.start: test={} agent={}", "singleExecutor", "summarizer");
        try {
            ExecutorAgent agent = ExecutorAgent.builder()
                    .name("summarizer")
                    .chatClient(chatClient)
                    .systemPrompt("You are a concise assistant. Answer in one sentence only.")
                    .build();

            log.info("agent.start: agent={} context={}", "summarizer", "What is Spring Boot?");
            AgentResult result = agent.execute(
                    AgentContext.of("What is Spring Boot?"));

            boolean ok = result.text() != null && !result.text().isBlank() && result.completed();
            log.info("agent.success: agent={} completed={} resultLength={}", "summarizer", result.completed(), result.text() != null ? result.text().length() : 0);
            return ok;
        } catch (Exception e) {
            log.error("agent.failure: agent={} test={}", "summarizer", "singleExecutor", e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2: CoordinatorAgent routes to the right executor
    // ─────────────────────────────────────────────────────────────
    private boolean test2_coordinatorRouting() {
        log.info("test.start: test={} agent={}", "coordinatorRouting", "router");
        try {
            ExecutorAgent techAgent = ExecutorAgent.builder()
                    .name("tech")
                    .chatClient(chatClient)
                    .systemPrompt("You are a technical expert. Start your response with [TECH].")
                    .build();

            ExecutorAgent creativeAgent = ExecutorAgent.builder()
                    .name("creative")
                    .chatClient(chatClient)
                    .systemPrompt("You are a creative writer. Start your response with [CREATIVE].")
                    .build();

            CoordinatorAgent coordinator = CoordinatorAgent.builder()
                    .name("router")
                    .executors(Map.of("tech", techAgent, "creative", creativeAgent))
                    .routingStrategy(RoutingStrategy.llmDriven(chatClient))
                    .build();

            log.info("agent.start: agent={} context={}", "router", "Write a haiku about Java programming");
            AgentResult result = coordinator.execute(
                    AgentContext.of("Write a haiku about Java programming"));

            boolean ok = result.text() != null && !result.text().isBlank();
            log.info("agent.success: agent={} completed={} resultLength={}", "router", ok, result.text() != null ? result.text().length() : 0);
            return ok;
        } catch (Exception e) {
            log.error("agent.failure: agent={} test={}", "router", "coordinatorRouting", e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3: AgentGraph — 3-step pipeline with state propagation
    // ─────────────────────────────────────────────────────────────
    private boolean test3_graphPipeline() {
        log.info("test.start: test={} graph={}", "graphPipeline", "research-pipeline");
        try {
            StateKey<String> TOPIC = StateKey.of("topic", String.class);

            ExecutorAgent researcher = ExecutorAgent.builder()
                    .name("researcher")
                    .chatClient(chatClient)
                    .systemPrompt("Extract the main topic from the user message. "
                                + "Reply with ONLY the topic, nothing else.")
                    .build();

            ExecutorAgent analyzer = ExecutorAgent.builder()
                    .name("analyzer")
                    .chatClient(chatClient)
                    .systemPrompt("List 3 key facts about the topic mentioned in the conversation. Be concise.")
                    .build();

            ExecutorAgent writer = ExecutorAgent.builder()
                    .name("writer")
                    .chatClient(chatClient)
                    .systemPrompt("Write a 2-sentence summary based on the conversation so far.")
                    .build();

            Agent analyzerWrapped = ctx -> {
                try { Thread.sleep(1500); } catch (Exception e) {}
                return analyzer.execute(ctx);
            };

            Agent writerWrapped = ctx -> {
                try { Thread.sleep(1500); } catch (Exception e) {}
                return writer.execute(ctx);
            };

            AtomicInteger nodeCount = new AtomicInteger();
            AgentListener counter = new AgentListener() {
                @Override
                public void onNodeEnter(String g, String node, AgentContext ctx) {
                    nodeCount.incrementAndGet();
                }
            };

            AgentGraph graph = AgentGraph.builder()
                    .name("research-pipeline")
                    .addNode("research", researcher)
                    .addNode("analyze", analyzerWrapped)
                    .addNode("write", writerWrapped)
                    .addEdge("research", "analyze")
                    .addEdge("analyze", "write")
                    .retryPolicy(RetryPolicy.once())
                    .listener(counter)
                    .build();

            AgentResult result = graph.invoke(
                    AgentContext.of("Tell me about WebAssembly and its impact on web development"));

            boolean ok = result.text() != null && nodeCount.get() == 3 && result.completed();
            log.info("test.success: test={} nodesVisited={} completed={}", "graphPipeline", nodeCount.get(), result.completed());
            return ok;
        } catch (Exception e) {
            log.error("test.failure: test={}", "graphPipeline", e);
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 4: Conditional routing — loop until "DONE"
    // ─────────────────────────────────────────────────────────────
    private boolean test4_conditionalRouting() {
        log.info("test.start: test={} graph={}", "conditionalRouting", "quality-loop");
        try {
            StateKey<Integer> ROUND = StateKey.of("round", Integer.class);
            AtomicInteger transitions = new AtomicInteger();

            ExecutorAgent checker = ExecutorAgent.builder()
                    .name("checker")
                    .chatClient(chatClient)
                    .systemPrompt("You are a quality checker. "
                                + "If this is the first round, reply with exactly: NEEDS_WORK. "
                                + "If previous messages mention NEEDS_WORK, reply with exactly: APPROVED.")
                    .build();

            io.github.datallmhub.agentflow4j.core.Agent roundTracker = ctx -> {
                int round = ctx.get(ROUND) != null ? ctx.get(ROUND) + 1 : 1;
                log.info("custom.state: node={} stateRound={}", "checker", round);
                try { Thread.sleep(1500); } catch (Exception e) {}
                AgentResult inner = checker.execute(ctx);
                return AgentResult.builder()
                        .text(inner.text())
                        .stateUpdates(Map.of(ROUND, round))
                        .completed(inner.completed())
                        .build();
            };

            io.github.datallmhub.agentflow4j.core.Agent finisher = ctx -> {
                log.info("custom.state: node={} finishRound={}", "finisher", ctx.get(ROUND));
                return AgentResult.ofText("Quality check passed after " + ctx.get(ROUND) + " round(s).");
            };

            AgentListener transitionCounter = new AgentListener() {
                @Override
                public void onTransition(String g, String from, String to) {
                    transitions.incrementAndGet();
                }
            };

            AgentGraph graph = AgentGraph.builder()
                    .name("quality-loop")
                    .addNode("check", roundTracker)
                    .addNode("finish", finisher)
                    .addEdge(Edge.onResult("check",
                            (ctx, result) -> result.text() != null
                                    && result.text().toUpperCase().contains("NEEDS_WORK"),
                            "check"))
                    .addEdge("check", "finish")
                    .maxIterations(5)
                    .listener(transitionCounter)
                    .build();

            AgentResult result = graph.invoke(
                    AgentContext.of("Check the quality of this document."));

            boolean ok = result.text() != null && transitions.get() >= 1;
            log.info("test.success: test={} transitions={} resultLength={}", "conditionalRouting", transitions.get(), result.text() != null ? result.text().length() : 0);
            return ok;
        } catch (Exception e) {
            log.error("test.failure: test={}", "conditionalRouting", e);
            return false;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
