# MCP tools

AF4J does not implement MCP. It governs the tools that Spring AI's MCP client already exposes: hand them to an `ExecutorAgent` and they go through the same `ToolPolicy`, tool-call audit and lifecycle hooks as any other tool.

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

The provider is queried on every run, so tools an MCP server adds or removes while the application is running are picked up without a restart. With Spring AI 1.0 the provider relists the server's tools each time. From Spring AI 1.1 it caches the list until it is notified of a change, so the new list shows up once that notification has arrived.

## Tool names

The name of an MCP tool is the one Spring AI exposes, and `ToolPolicy` rules, `ToolCallRecord.name()` and `onToolCall` all use that name.

The naming rule depends on the Spring AI line. Spring AI 1.0 prefixes each tool with the client name: tool `refund_order` served through the client `orders` becomes `orders_refund_order`, which `McpToolUtils.prefixedToolName(client, tool)` computes. From Spring AI 1.1 the default exposes the tool under its own name, `refund_order`.

!!! warning
    A `ToolPolicy` rule written for a name the provider does not expose never matches. After changing the Spring AI version, check the names with `mcpTools.getToolCallbacks()` before relying on an allow or deny list.

## Do not register MCP tools on the ChatClient

!!! warning
    Tools registered on the `ChatClient` itself, for example with `ChatClient.builder(model).defaultToolCallbacks(mcpTools)`, are invisible to AF4J: they bypass the `ToolPolicy`, are not recorded as `ToolCallRecord`s and do not reach `onToolCall`. Register them on the `ExecutorAgent` with `toolProviders(...)` instead.

## What you get

| Concern | Behaviour for MCP tools |
|---|---|
| `ToolPolicy` | Checked before the call reaches the MCP server; a denied call never leaves the process and the model receives the denial reason as the tool result |
| Audit | Every call, allowed or denied, is a `ToolCallRecord` on the node's `AgentResult` |
| Listeners | `AgentListener.onToolCall` fires once per call |
| Streaming | `AgentEvent.ToolCallStart` / `ToolCallEnd` are emitted from `invokeStream` |
