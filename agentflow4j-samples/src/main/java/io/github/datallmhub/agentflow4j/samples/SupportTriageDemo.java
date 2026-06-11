package io.github.datallmhub.agentflow4j.samples;

import java.util.Map;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.Edge;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.mistralai.MistralAiChatModel;
import org.springframework.ai.mistralai.MistralAiChatOptions;
import org.springframework.ai.mistralai.api.MistralAiApi;

/**
 * Real-world B2B example: routing customer support tickets through a graph
 * of specialised agents.
 *
 * <p>Flow:
 * <pre>
 *   triage  →  (refund | billing | technical | general)  →  policy  →  reply
 * </pre>
 *
 * <p>Hybrid execution:
 * <ul>
 *   <li>If {@code MISTRAL_API_KEY} is set, the triage classifier and the
 *       specialist drafters call the real Mistral API.</li>
 *   <li>Otherwise the agents fall back to deterministic stubs — the demo
 *       still runs end-to-end so anyone can clone-and-go.</li>
 * </ul>
 */
public class SupportTriageDemo {

    static final String RESET  = "[0m";
    static final String BOLD   = "[1m";
    static final String CYAN   = "[36m";
    static final String GREEN  = "[32m";
    static final String YELLOW = "[33m";
    static final String MAGENTA= "[35m";
    static final String DIM    = "[2m";

    enum Category { REFUND, BILLING, TECHNICAL, GENERAL }

    static final StateKey<String>   TICKET_ID     = StateKey.of("ticket.id",       String.class);
    static final StateKey<Category> CATEGORY      = StateKey.of("ticket.category", Category.class);
    static final StateKey<String>   DRAFT         = StateKey.of("ticket.draft",    String.class);
    static final StateKey<Boolean>  POLICY_PASSED = StateKey.of("policy.passed",   Boolean.class);

    public static void main(String[] args) {
        ChatClient chat = mistralChatClientOrNull();
        AgentGraph graph = buildGraph(chat);

        String body = "Hi, I was charged twice for my October subscription. "
                    + "Can you refund the duplicate? Order #4521.";

        AgentContext ctx = AgentContext.of(body).with(TICKET_ID, "4521");

        System.out.println(BOLD + "=== Customer Support Triage ===" + RESET);
        System.out.println(DIM + "[mode]      " + (chat != null ? "LIVE (Mistral)" : "STUB (no MISTRAL_API_KEY)") + RESET + "\n");
        System.out.println(BOLD + "Ticket #" + ctx.get(TICKET_ID) + RESET);
        System.out.println(DIM + "  \"" + body + "\"" + RESET + "\n");

        AgentResult result = graph.invoke(ctx);

        System.out.println("\n" + BOLD + "Final response sent to the customer:" + RESET);
        System.out.println(DIM + "─".repeat(60) + RESET);
        System.out.println(GREEN + result.text() + RESET);
        System.out.println(DIM + "─".repeat(60) + RESET);
    }

    public static AgentGraph buildGraph(@Nullable ChatClient chat) {
        boolean live = chat != null;

        Agent triage    = live ? llmTriage(chat)                                                   : stubTriage();
        Agent refund    = live ? llmSpecialist(chat, "refund",    "Acknowledge the duplicate charge and promise a refund within 5 business days.")
                               : stubSpecialist("refund",    "We see the duplicate charge and will refund it within 5 business days.");
        Agent billing   = live ? llmSpecialist(chat, "billing",   "Confirm the billing detail update and reassure the customer.")
                               : stubSpecialist("billing",   "Your billing details have been updated on the account.");
        Agent technical = live ? llmSpecialist(chat, "technical", "Suggest a basic troubleshooting step and promise escalation if it fails.")
                               : stubSpecialist("technical", "Please try clearing your cache and signing in again — if the issue persists we'll escalate.");
        Agent general   = live ? llmSpecialist(chat, "general",   "Thank the customer and promise a follow-up.")
                               : stubSpecialist("general",   "Thanks for reaching out — a team member will follow up shortly.");

        Agent policy = ctx -> {
            String draft = ctx.get(DRAFT);
            boolean ok = draft != null && !draft.toLowerCase().contains("password");
            System.out.println(MAGENTA + "[policy]" + RESET + "    passed = " + ok);
            return AgentResult.builder()
                    .text(ok ? "policy ok" : "policy violation")
                    .stateUpdates(Map.of(POLICY_PASSED, ok))
                    .completed(true)
                    .build();
        };

        Agent reply = ctx -> {
            Boolean ok = ctx.get(POLICY_PASSED);
            String draft = ctx.get(DRAFT);
            String body = Boolean.TRUE.equals(ok)
                    ? "Hi,\n\n" + draft + "\n\n— Customer Support"
                    : "Hi,\n\nYour ticket is being escalated to a human agent.\n\n— Customer Support";
            System.out.println(GREEN + "[reply]" + RESET + "     drafted (" + body.length() + " chars)");
            return AgentResult.ofText(body);
        };

        return AgentGraph.builder()
                .name("support-triage")
                .addNode("triage",    triage)
                .addNode("refund",    refund)
                .addNode("billing",   billing)
                .addNode("technical", technical)
                .addNode("general",   general)
                .addNode("policy",    policy)
                .addNode("reply",     reply)
                .addEdge(Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.REFUND,    "refund"))
                .addEdge(Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.BILLING,   "billing"))
                .addEdge(Edge.conditional("triage", ctx -> ctx.get(CATEGORY) == Category.TECHNICAL, "technical"))
                .addEdge("triage", "general")
                .addEdge("refund",    "policy")
                .addEdge("billing",   "policy")
                .addEdge("technical", "policy")
                .addEdge("general",   "policy")
                .addEdge("policy",    "reply")
                .build();
    }

    private static Agent llmTriage(ChatClient chat) {
        return ctx -> {
            String reply = chat.prompt()
                    .system("Classify the customer support ticket into exactly one of: "
                          + "REFUND, BILLING, TECHNICAL, GENERAL. Reply with only the label.")
                    .user(lastUser(ctx))
                    .call()
                    .content();
            Category cat = parseCategory(reply);
            System.out.println(CYAN + "[triage]" + RESET + "    Mistral said \"" + reply.trim() + "\" → " + cat);
            return AgentResult.builder()
                    .text("classified")
                    .stateUpdates(Map.of(CATEGORY, cat))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubTriage() {
        return ctx -> {
            Category cat = stubClassify(lastUser(ctx));
            System.out.println(CYAN + "[triage]" + RESET + "    category = " + cat);
            return AgentResult.builder()
                    .text("classified")
                    .stateUpdates(Map.of(CATEGORY, cat))
                    .completed(true)
                    .build();
        };
    }

    private static Agent llmSpecialist(ChatClient chat, String label, String policyHint) {
        return ctx -> {
            String reply = chat.prompt()
                    .system("You are a customer-support agent in the " + label.toUpperCase()
                          + " team. Write one short, polite paragraph for the customer. "
                          + "Policy: " + policyHint)
                    .user(lastUser(ctx))
                    .call()
                    .content();
            System.out.println(YELLOW + "[" + label + "]" + RESET + "    Mistral drafted (" + reply.length() + " chars)");
            return AgentResult.builder()
                    .text(reply)
                    .stateUpdates(Map.of(DRAFT, reply))
                    .completed(true)
                    .build();
        };
    }

    private static Agent stubSpecialist(String label, String draft) {
        return ctx -> {
            System.out.println(YELLOW + "[" + label + "]" + RESET + "    stub drafted");
            return AgentResult.builder()
                    .text(draft)
                    .stateUpdates(Map.of(DRAFT, draft))
                    .completed(true)
                    .build();
        };
    }

    @Nullable
    private static ChatClient mistralChatClientOrNull() {
        String key = System.getenv("MISTRAL_API_KEY");
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            MistralAiApi api = new MistralAiApi(key);
            MistralAiChatModel model = MistralAiChatModel.builder()
                    .mistralAiApi(api)
                    .defaultOptions(MistralAiChatOptions.builder()
                            .model("mistral-small-latest")
                            .temperature(0.3)
                            .build())
                    .build();
            return ChatClient.builder(model).build();
        }
        catch (Throwable t) {
            System.err.println("Could not init Mistral (" + t.getMessage() + ") — falling back to STUB");
            return null;
        }
    }

    private static String lastUser(AgentContext ctx) {
        return ctx.messages().get(ctx.messages().size() - 1).getText();
    }

    private static Category parseCategory(String reply) {
        String upper = reply == null ? "" : reply.trim().toUpperCase();
        for (Category c : Category.values()) {
            if (upper.contains(c.name())) return c;
        }
        return Category.GENERAL;
    }

    private static Category stubClassify(String text) {
        String t = text.toLowerCase();
        if (t.contains("refund") || t.contains("charged twice")) return Category.REFUND;
        if (t.contains("invoice") || t.contains("bill"))         return Category.BILLING;
        if (t.contains("not working") || t.contains("error"))    return Category.TECHNICAL;
        return Category.GENERAL;
    }
}
