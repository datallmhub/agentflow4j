package io.github.datallmhub.agentflow4j.squad;

import java.util.concurrent.atomic.AtomicBoolean;

import io.github.datallmhub.agentflow4j.graph.ToolPolicy;
import io.github.datallmhub.agentflow4j.graph.ToolPolicyViolation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyToolCallbackTests {

    @Test
    void allowsCallWhenPolicyApproves() {
        AtomicBoolean delegateCalled = new AtomicBoolean();
        ToolCallback delegate = stubCallback("web.search", input -> {
            delegateCalled.set(true);
            return "result";
        });

        PolicyToolCallback callback = new PolicyToolCallback(delegate, ToolPolicy.ALLOW_ALL);
        String result = callback.call("{\"q\":\"hello\"}");

        assertThat(result).isEqualTo("result");
        assertThat(delegateCalled).isTrue();
    }

    @Test
    void deniesCallAndDoesNotInvokeDelegateWhenPolicyRefuses() {
        AtomicBoolean delegateCalled = new AtomicBoolean();
        ToolCallback delegate = stubCallback("shell.execute", input -> {
            delegateCalled.set(true);
            return "should not run";
        });

        PolicyToolCallback callback = new PolicyToolCallback(delegate,
                ToolPolicy.denyList("shell.execute"));

        assertThatThrownBy(() -> callback.call("{\"cmd\":\"rm -rf /\"}"))
                .isInstanceOf(ToolExecutionException.class)
                .hasCauseInstanceOf(ToolPolicyViolation.class)
                .hasMessageStartingWith("tool policy denied call to 'shell.execute'");
        assertThat(delegateCalled).isFalse();
    }

    @Test
    void violationCarriesToolNameArgsAndReason() {
        ToolCallback delegate = stubCallback("payment.transfer", input -> "ignored");
        ToolPolicy refuseAbove1000 = ToolPolicy.when(
                (name, args) -> !(args.get("amount") instanceof Number n && n.doubleValue() > 1000.0),
                "amount above 1000 requires approval");

        PolicyToolCallback callback = new PolicyToolCallback(delegate, refuseAbove1000);

        try {
            callback.call("{\"amount\":5000}");
            assertThat(false).as("expected ToolExecutionException").isTrue();
        }
        catch (ToolExecutionException ex) {
            ToolPolicyViolation violation = (ToolPolicyViolation) ex.getCause();
            assertThat(violation.toolName()).isEqualTo("payment.transfer");
            assertThat(violation.arguments()).containsEntry("amount", 5000);
            assertThat(violation.reason()).isEqualTo("amount above 1000 requires approval");
        }
    }

    @Test
    void malformedJsonStillReachesPolicyWithRawArgument() {
        ToolCallback delegate = stubCallback("any.tool", input -> "fine");
        ToolPolicy capture = (name, args) -> {
            assertThat(args).containsKey("_raw");
            return ToolPolicy.Decision.allow();
        };
        new PolicyToolCallback(delegate, capture).call("not-json");
    }

    private static ToolCallback stubCallback(String name, java.util.function.Function<String, String> impl) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name + " stub")
                        .inputSchema("{}")
                        .build();
            }
            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }
            @Override
            public String call(String toolInput) {
                return impl.apply(toolInput);
            }
        };
    }
}
