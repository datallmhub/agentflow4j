package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunOptionsTests {

    @Test
    void runOptionsCombineRunIdAndTimeout() {
        RunOptions options = RunOptions.ofRunId("r-1").withTimeout(Duration.ofSeconds(5));

        assertThat(options.runId()).isEqualTo("r-1");
        assertThat(options.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(RunOptions.defaults().runId()).isNull();
        assertThat(RunOptions.defaults().timeout()).isNull();
    }

    @Test
    void runOptionsRejectNegativeTimeout() {
        assertThatThrownBy(() -> RunOptions.ofTimeout(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resumeOptionsAccumulateApprovalsAndMessages() {
        ResumeOptions options = ResumeOptions.ofApproval("a")
                .withApproval("b")
                .withMessages(new UserMessage("one"))
                .withMessages(new UserMessage("two"));

        assertThat(options.approvedNodes()).containsExactlyInAnyOrder("a", "b");
        assertThat(options.messages()).extracting(m -> m.getText()).containsExactly("one", "two");
        assertThat(ResumeOptions.none().approvedNodes()).isEmpty();
    }

    @Test
    void resumeApprovesSeveralNodesAtOnce() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        AgentGraph graph = AgentGraph.builder()
                .addNode("transfer", ctx -> AgentResult.ofText("transferred"))
                .addNode("refund", ctx -> AgentResult.ofText("refunded"))
                .addEdge("transfer", "refund")
                .approvalGate(ApprovalGate.requireFor("transfer", "refund"))
                .checkpointStore(store)
                .build();

        graph.invoke(AgentContext.of("go"), RunOptions.ofRunId("r-2"));
        AgentResult result = graph.resume("r-2", ResumeOptions.ofApproval("transfer").withApproval("refund"));

        assertThat(result.completed()).isTrue();
        assertThat(result.text()).isEqualTo("refunded");
    }
}
