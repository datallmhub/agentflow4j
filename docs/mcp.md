# MCP tools

AgentFlow4J does not implement MCP. It governs the tools that Spring AI's MCP client already exposes: hand them to an `ExecutorAgent` and they go through the same `ToolPolicy`, tool-call audit and lifecycle hooks as any other tool.

## Wiring

With `spring-ai-starter-mcp-client` on the classpath, Spring Boot creates a `ToolCallbackProvider` bean for the configured MCP servers. Pass it to the executor with `toolProviders(...)`:

```java
@Bean
ExecutorAgent support(ChatClient.Builder chat, ToolCallbackProvider mcpTools) {
    return ExecutorAgent.builder()
            .name("support")
            .chatClient(chat.build())
            .toolProviders(mcpTools)
            .toolPolicy(ToolPolicy.denyList("orders_refund_order"))
            .build();
}
```

The provider is queried on every run, so tools an MCP server adds or removes while the application is running are picked up without a restart.

## Tool names

Spring AI prefixes each MCP tool with the client name: tool `refund_order` served through the client `orders` becomes `orders_refund_order`. `ToolPolicy` rules, `ToolCallRecord.name()` and `onToolCall` all use the prefixed name. `McpToolUtils.prefixedToolName(client, tool)` computes it.

## Do not register MCP tools on the ChatClient

!!! warning
    Tools registered on the `ChatClient` itself, for example with `ChatClient.builder(model).defaultToolCallbacks(mcpTools)`, are invisible to AgentFlow4J: they bypass the `ToolPolicy`, are not recorded as `ToolCallRecord`s and do not reach `onToolCall`. Register them on the `ExecutorAgent` with `toolProviders(...)` instead.

## What you get

| Concern | Behaviour for MCP tools |
|---|---|
| `ToolPolicy` | Checked before the call reaches the MCP server; a denied call never leaves the process and the model receives the denial reason as the tool result |
| Audit | Every call, allowed or denied, is a `ToolCallRecord` on the node's `AgentResult` |
| Listeners | `AgentListener.onToolCall` fires once per call |
| Streaming | `AgentEvent.ToolCallStart` / `ToolCallEnd` are emitted from `invokeStream` |
