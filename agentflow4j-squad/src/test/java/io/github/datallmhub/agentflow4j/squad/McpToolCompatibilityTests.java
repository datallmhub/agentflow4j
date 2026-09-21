package io.github.datallmhub.agentflow4j.squad;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.ToolCallRecord;
import io.github.datallmhub.agentflow4j.graph.AgentGraph;
import io.github.datallmhub.agentflow4j.graph.AgentListener;
import io.github.datallmhub.agentflow4j.graph.ToolPolicy;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that tools exposed by an MCP server through Spring AI's
 * {@link SyncMcpToolCallbackProvider} are governed like any other tool once
 * handed to {@link ExecutorAgent}: the {@link ToolPolicy} applies, every call
 * is recorded, and the graph's listeners see it.
 */
class McpToolCompatibilityTests {

    private static final String CLIENT = "orders";
    private static final String LOOKUP = McpToolUtils.prefixedToolName(CLIENT, "lookup_order");
    private static final String REFUND = McpToolUtils.prefixedToolName(CLIENT, "refund_order");

    private McpSyncClient mcpClient;

    @BeforeEach
    void mcpServerWithTwoTools() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object",
                Map.of("orderId", Map.of("type", "string")), List.of("orderId"), null, null, null);
        mcpClient = mock(McpSyncClient.class);
        when(mcpClient.getClientInfo()).thenReturn(new McpSchema.Implementation(CLIENT, "1.0"));
        when(mcpClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                McpSchema.Tool.builder().name("lookup_order").description("Find an order").inputSchema(schema).build(),
                McpSchema.Tool.builder().name("refund_order").description("Refund an order").inputSchema(schema).build()),
                null));
        when(mcpClient.callTool(any())).thenAnswer(inv -> {
            McpSchema.CallToolRequest request = inv.getArgument(0);
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(request.name() + " ok")), false);
        });
    }

    @Test
    void mcpToolsGoThroughToolPolicyAndAudit() {
        ToolCallingModel model = new ToolCallingModel(List.of(LOOKUP, REFUND));
        ExecutorAgent agent = ExecutorAgent.builder()
                .chatClient(ChatClient.create(model))
                .toolProviders(new SyncMcpToolCallbackProvider(mcpClient))
                .toolPolicy(ToolPolicy.denyList(REFUND))
                .build();

        AgentResult result = agent.execute(AgentContext.of("refund order 42"));

        assertThat(result.toolCalls()).extracting(ToolCallRecord::name).containsExactly(LOOKUP, REFUND);
        assertThat(result.toolCalls().get(0).success()).isTrue();
        assertThat(result.toolCalls().get(0).arguments()).containsEntry("orderId", "42");
        assertThat(result.toolCalls().get(1).success()).isFalse();
        assertThat(result.toolCalls().get(1).error()).startsWith("ToolPolicyViolation: tool policy denied");
        assertThat(result.hasError()).isFalse();
        assertThat(model.toolResults).containsExactly(
                LOOKUP + " -> [{\"text\":\"lookup_order ok\"}]",
                REFUND + " -> tool policy denied call to '" + REFUND + "': tool '" + REFUND + "' is denied");
        verify(mcpClient, never()).callTool(new McpSchema.CallToolRequest("refund_order", Map.of("orderId", "42")));
    }

    @Test
    void mcpToolCallsReachGraphListeners() {
        ToolCallingModel model = new ToolCallingModel(List.of(LOOKUP));
        ExecutorAgent agent = ExecutorAgent.builder()
                .chatClient(ChatClient.create(model))
                .toolProviders(new SyncMcpToolCallbackProvider(mcpClient))
                .build();
        List<String> seen = new CopyOnWriteArrayList<>();
        AgentGraph graph = AgentGraph.builder()
                .addNode("support", agent)
                .listener(new AgentListener() {
                    @Override
                    public void onToolCall(String graphName, String nodeName, ToolCallRecord call) {
                        seen.add(nodeName + ":" + call.name());
                    }
                })
                .build();

        graph.invoke(AgentContext.of("where is order 42"));

        assertThat(seen).containsExactly("support:" + LOOKUP);
    }

    @Test
    void toolsAddedByTheServerAfterBuildArePickedUp() {
        ToolCallingModel model = new ToolCallingModel(List.of());
        ExecutorAgent agent = ExecutorAgent.builder()
                .chatClient(ChatClient.create(model))
                .toolProviders(new SyncMcpToolCallbackProvider(mcpClient))
                .build();

        agent.execute(AgentContext.of("hi"));
        when(mcpClient.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(
                McpSchema.Tool.builder().name("cancel_order").description("Cancel")
                        .inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null))
                        .build()), null));
        agent.execute(AgentContext.of("hi again"));

        assertThat(model.offeredTools.get(1)).containsExactly(McpToolUtils.prefixedToolName(CLIENT, "cancel_order"));
    }

    /**
     * Stands in for a provider model with internal tool execution: it requests
     * each scripted tool, lets Spring AI's {@link ToolCallingManager} run them
     * exactly as a real provider does, then answers with plain text.
     */
    private static final class ToolCallingModel implements ChatModel {

        private final List<String> toolsToCall;
        final List<List<String>> offeredTools = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();

        ToolCallingModel(List<String> toolsToCall) {
            this.toolsToCall = toolsToCall;
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            List<ToolCallback> offered = prompt.getOptions() instanceof ToolCallingChatOptions options
                    ? options.getToolCallbacks() : List.of();
            offeredTools.add(offered.stream().map(t -> t.getToolDefinition().name()).toList());
            if (!toolsToCall.isEmpty()) {
                List<AssistantMessage.ToolCall> calls = toolsToCall.stream()
                        .map(name -> new AssistantMessage.ToolCall("call-" + name, "function", name,
                                "{\"orderId\":\"42\"}"))
                        .toList();
                ChatResponse toolRequest = new ChatResponse(List.of(
                        new Generation(new AssistantMessage("", Map.of(), calls))));
                ToolExecutionResult execution = ToolCallingManager.builder().build()
                        .executeToolCalls(prompt, toolRequest);
                List<Message> history = execution.conversationHistory();
                ToolResponseMessage responses = (ToolResponseMessage) history.get(history.size() - 1);
                responses.getResponses().forEach(r -> toolResults.add(r.name() + " -> " + r.responseData()));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }
    }
}
