package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.List;
import java.util.Map;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.InterruptRequest;
import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.ApprovalGate;
import io.github.datallmhub.agentflow4j.graph.Checkpoint;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JacksonCheckpointCodecTests {

    public record Report(String topic, int priority) {}

    private static final StateKey<Report> REPORT = StateKey.of("report", Report.class);

    @Test
    void roundTripPreservesAllMessageRolesAndState() {
        StateTypeRegistry registry = new StateTypeRegistry().register(REPORT);
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(registry);

        AgentContext ctx = AgentContext.empty()
                .withMessages(List.of(
                        new SystemMessage("you are helpful"),
                        new UserMessage("hello"),
                        new AssistantMessage("hi", Map.of(), List.of(
                                new AssistantMessage.ToolCall("t1", "function", "search", "{\"q\":\"x\"}"))),
                        new ToolResponseMessage(List.of(
                                new ToolResponseMessage.ToolResponse("t1", "search", "\"ok\"")))))
                .with(REPORT, new Report("security", 7));

        Checkpoint original = new Checkpoint("run-1", "next", ctx, 3,
                InterruptRequest.of("await-human"));

        String json = codec.encode(original);
        Checkpoint decoded = codec.decode(json);

        assertThat(decoded.runId()).isEqualTo("run-1");
        assertThat(decoded.nextNode()).isEqualTo("next");
        assertThat(decoded.iterations()).isEqualTo(3);
        assertThat(decoded.interrupt()).isNotNull();
        assertThat(decoded.interrupt().reason()).isEqualTo("await-human");

        List<org.springframework.ai.chat.messages.Message> msgs = decoded.context().messages();
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(msgs.get(1)).isInstanceOf(UserMessage.class);
        assertThat(msgs.get(2)).isInstanceOfSatisfying(AssistantMessage.class, am -> {
            assertThat(am.getText()).isEqualTo("hi");
            assertThat(am.getToolCalls()).hasSize(1);
            assertThat(am.getToolCalls().get(0).name()).isEqualTo("search");
            assertThat(am.getToolCalls().get(0).arguments()).contains("\"q\"");
        });
        assertThat(msgs.get(3)).isInstanceOfSatisfying(ToolResponseMessage.class, trm -> {
            assertThat(trm.getResponses()).hasSize(1);
            assertThat(trm.getResponses().get(0).name()).isEqualTo("search");
        });

        assertThat(decoded.context().get(REPORT)).isEqualTo(new Report("security", 7));
    }

    @Test
    void encodeFailsWhenStateTypeIsNotRegistered() {
        StateTypeRegistry empty = new StateTypeRegistry();
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(empty);

        AgentContext ctx = AgentContext.empty().with(REPORT, new Report("x", 1));
        Checkpoint cp = new Checkpoint("r", "n", ctx, 0, null);

        assertThatThrownBy(() -> codec.encode(cp))
                .isInstanceOf(CheckpointSerializationException.class)
                .hasMessageContaining("State type not registered");
    }

    @Test
    void decodeFailsWhenLogicalTypeIsUnknown() {
        StateTypeRegistry registry = new StateTypeRegistry();
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(registry);

        String payload = "{\"version\":1,\"runId\":\"r\",\"nextNode\":\"n\",\"iterations\":0,"
                + "\"messages\":[],"
                + "\"state\":[{\"key\":\"foo\",\"type\":\"not-registered\",\"value\":{\"a\":1}}]}";

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(CheckpointSerializationException.class)
                .hasMessageContaining("Unknown logical state type 'not-registered'");
    }

    @Test
    void decodeFailsWhenVersionIsUnsupported() {
        StateTypeRegistry registry = new StateTypeRegistry();
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(registry);

        String payload = "{\"version\":99,\"runId\":\"r\",\"nextNode\":\"n\",\"iterations\":0,"
                + "\"messages\":[],\"state\":[]}";

        assertThatThrownBy(() -> codec.decode(payload))
                .isInstanceOf(CheckpointSerializationException.class)
                .hasMessageContaining("Unsupported checkpoint version: 99");
    }

    @Test
    void encodeOmitsInterruptWhenAbsent() {
        StateTypeRegistry registry = new StateTypeRegistry();
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(registry);

        Checkpoint cp = new Checkpoint("r", "n", AgentContext.empty(), 0, null);
        String json = codec.encode(cp);

        assertThat(json).doesNotContain("interruptReason");
    }

    @Test
    void registryRejectsConflictingBindings() {
        StateTypeRegistry reg = new StateTypeRegistry().register("x", String.class);
        assertThatThrownBy(() -> reg.register("x", Integer.class))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approvalMarkerSurvivesRoundTripWithoutExplicitRegistration() {
        JacksonCheckpointCodec codec = new JacksonCheckpointCodec(new StateTypeRegistry());
        AgentContext ctx = AgentContext.of("go")
                .with(ApprovalGate.APPROVED_KEY, java.util.Set.of("payment.transfer"));

        Checkpoint decoded = codec.decode(codec.encode(new Checkpoint("run-1", "refund", ctx, 1, null)));

        assertThat(decoded.context().get(ApprovalGate.APPROVED_KEY)).containsExactly("payment.transfer");
    }
}
