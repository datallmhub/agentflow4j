package io.github.datallmhub.agentflow4j.checkpoint;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * Builds the two Spring AI messages that have no construction API common to
 * every supported line: Spring AI 1.0 only has public constructors, and 1.1
 * made them protected in favour of builders. The right one is picked
 * reflectively so a single jar restores checkpoints on both.
 */
final class SpringAiMessages {

    private static final boolean ASSISTANT_BUILDER = hasBuilder(AssistantMessage.class);
    private static final boolean TOOL_RESPONSE_BUILDER = hasBuilder(ToolResponseMessage.class);

    private SpringAiMessages() {
    }

    static AssistantMessage assistant(String text, Map<String, Object> metadata,
                                      List<AssistantMessage.ToolCall> toolCalls) {
        return create(AssistantMessage.class, () -> {
            if (!ASSISTANT_BUILDER) {
                return AssistantMessage.class.getConstructor(String.class, Map.class, List.class)
                        .newInstance(text, metadata, toolCalls);
            }
            Object builder = AssistantMessage.class.getMethod("builder").invoke(null);
            Class<?> type = builder.getClass();
            type.getMethod("content", String.class).invoke(builder, text);
            type.getMethod("properties", Map.class).invoke(builder, metadata);
            type.getMethod("toolCalls", List.class).invoke(builder, toolCalls);
            return type.getMethod("build").invoke(builder);
        });
    }

    static ToolResponseMessage toolResponse(List<ToolResponseMessage.ToolResponse> responses,
                                            Map<String, Object> metadata) {
        return create(ToolResponseMessage.class, () -> {
            if (!TOOL_RESPONSE_BUILDER) {
                return ToolResponseMessage.class.getConstructor(List.class, Map.class)
                        .newInstance(responses, metadata);
            }
            Object builder = ToolResponseMessage.class.getMethod("builder").invoke(null);
            Class<?> type = builder.getClass();
            type.getMethod("responses", List.class).invoke(builder, responses);
            type.getMethod("metadata", Map.class).invoke(builder, metadata);
            return type.getMethod("build").invoke(builder);
        });
    }

    private static boolean hasBuilder(Class<?> messageType) {
        try {
            messageType.getMethod("builder");
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    private static <T> T create(Class<T> messageType, ReflectiveCall call) {
        try {
            return messageType.cast(call.invoke());
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw unsupported(messageType, ex);
        } catch (ReflectiveOperationException ex) {
            throw unsupported(messageType, ex);
        }
    }

    private static IllegalStateException unsupported(Class<?> messageType, Exception cause) {
        return new IllegalStateException(
                "Cannot build a " + messageType.getSimpleName() + " with this Spring AI version", cause);
    }

    @FunctionalInterface
    private interface ReflectiveCall {
        Object invoke() throws ReflectiveOperationException;
    }
}
