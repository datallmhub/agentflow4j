package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import io.github.datallmhub.agentflow4j.core.StateBag;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

public final class JacksonCheckpointCodec implements CheckpointCodec {

    private final ObjectMapper mapper;
    private final StateTypeRegistry registry;

    public JacksonCheckpointCodec(StateTypeRegistry registry) {
        this(new ObjectMapper(), registry);
    }

    public JacksonCheckpointCodec(ObjectMapper mapper, StateTypeRegistry registry) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public String encode(Checkpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            return mapper.writeValueAsString(toDto(checkpoint));
        } catch (JsonProcessingException ex) {
            throw new CheckpointSerializationException("Failed to encode checkpoint " + checkpoint.runId(), ex);
        }
    }

    @Override
    public Checkpoint decode(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            CheckpointDto dto = mapper.readValue(payload, CheckpointDto.class);
            return fromDto(dto);
        } catch (JsonProcessingException ex) {
            throw new CheckpointSerializationException("Failed to decode checkpoint payload", ex);
        }
    }

    private CheckpointDto toDto(Checkpoint cp) {
        List<MessageDto> messages = new ArrayList<>(cp.context().messages().size());
        for (Message m : cp.context().messages()) {
            messages.add(toMessageDto(m));
        }
        List<StateEntryDto> state = new ArrayList<>(cp.context().state().size());
        for (StateKey<?> key : cp.context().state().keys()) {
            Object value = rawGet(cp.context().state(), key);
            String logical = registry.logicalNameOf(key.type())
                    .orElseThrow(() -> new CheckpointSerializationException(
                            "State type not registered for key '" + key.name()
                                    + "' (" + key.type().getName()
                                    + "). Register it with StateTypeRegistry.register(...)."));
            state.add(new StateEntryDto(key.name(), logical, value));
        }
        InterruptRequest ir = cp.interrupt();
        return new CheckpointDto(
                CheckpointDto.CURRENT_VERSION,
                cp.runId(),
                cp.nextNode(),
                cp.nextNodes(),
                cp.iterations(),
                ir == null ? null : ir.reason(),
                messages,
                state);
    }

    private Checkpoint fromDto(CheckpointDto dto) {
        if (dto.version() < 1 || dto.version() > CheckpointDto.CURRENT_VERSION) {
            throw new CheckpointSerializationException("Unsupported checkpoint version: " + dto.version()
                    + " (supported: 1.." + CheckpointDto.CURRENT_VERSION + ")");
        }
        List<Message> messages = new ArrayList<>();
        if (dto.messages() != null) {
            for (MessageDto md : dto.messages()) {
                messages.add(fromMessageDto(md));
            }
        }
        StateBag bag = StateBag.empty();
        if (dto.state() != null) {
            Map<StateKey<?>, Object> updates = new HashMap<>();
            for (StateEntryDto entry : dto.state()) {
                Class<?> type = registry.resolve(entry.type())
                        .orElseThrow(() -> new CheckpointSerializationException(
                                "Unknown logical state type '" + entry.type()
                                        + "' for key '" + entry.key()
                                        + "'. Register it with StateTypeRegistry.register(...)."));
                Object value = entry.value() == null ? null : mapper.convertValue(entry.value(), type);
                if (value != null) {
                    updates.put(rawKey(entry.key(), type), value);
                }
            }
            bag = bag.merge(updates);
        }
        AgentContext ctx = AgentContext.empty().withMessages(messages).withState(bag);
        InterruptRequest ir = dto.interruptReason() == null ? null : InterruptRequest.of(dto.interruptReason());
        return new Checkpoint(dto.runId(), dto.frontier(), ctx, dto.iterations(), ir);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StateKey<?> rawKey(String name, Class<?> type) {
        return StateKey.of(name, (Class) type);
    }

    @SuppressWarnings("unchecked")
    private static <T> T rawGet(StateBag bag, StateKey<?> key) {
        return (T) bag.get((StateKey<T>) key);
    }

    private static MessageDto toMessageDto(Message m) {
        String role = m.getMessageType() == null ? "user" : m.getMessageType().getValue();
        String text = m.getText();
        List<ToolCallDto> toolCalls = null;
        Map<String, Object> metadata = m.getMetadata() == null ? null : new HashMap<>(m.getMetadata());
        if (m instanceof AssistantMessage am && am.hasToolCalls()) {
            toolCalls = new ArrayList<>(am.getToolCalls().size());
            for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                toolCalls.add(new ToolCallDto(tc.id(), tc.name(), tc.arguments()));
            }
        } else if (m instanceof ToolResponseMessage trm) {
            toolCalls = new ArrayList<>(trm.getResponses().size());
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                toolCalls.add(new ToolCallDto(tr.id(), tr.name(), tr.responseData()));
            }
        }
        return new MessageDto(MessageDto.CURRENT_VERSION, role, text, toolCalls, metadata);
    }

    private static Message fromMessageDto(MessageDto dto) {
        String role = dto.role() == null ? MessageType.USER.getValue() : dto.role();
        String text = dto.text() == null ? "" : dto.text();
        Map<String, Object> metadata = dto.metadata() == null ? Map.of() : dto.metadata();
        MessageType type = MessageType.fromValue(role);
        return switch (type) {
            case USER -> new UserMessage(text);
            case SYSTEM -> new SystemMessage(text);
            case ASSISTANT -> {
                List<AssistantMessage.ToolCall> calls = new ArrayList<>();
                if (dto.toolCalls() != null) {
                    for (ToolCallDto tc : dto.toolCalls()) {
                        calls.add(new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.arguments()));
                    }
                }
                yield new AssistantMessage(text, metadata, calls);
            }
            case TOOL -> {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                if (dto.toolCalls() != null) {
                    for (ToolCallDto tc : dto.toolCalls()) {
                        responses.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), tc.arguments()));
                    }
                }
                yield new ToolResponseMessage(responses, metadata);
            }
        };
    }
}
