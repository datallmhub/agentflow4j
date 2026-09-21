package io.github.datallmhub.agentflow4j.graph;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.StateKey;
import org.jspecify.annotations.Nullable;

/**
 * Human-in-the-loop gate evaluated <em>before</em> a node executes. Pairs
 * with {@link ToolPolicy} (what may run) and {@link StatePolicy} (what may
 * change) to complete the governed-execution story.
 *
 * <p>When a gate returns {@link Outcome#REQUIRE_APPROVAL}, the graph emits
 * an {@link io.github.datallmhub.agentflow4j.core.InterruptRequest},
 * persists a checkpoint, and returns an interrupted {@code AgentResult}.
 * A human (or upstream system) then calls
 * {@link AgentGraph#resume(String, ResumeOptions)} with
 * {@link ResumeOptions#ofApproval(String)} to mark the node as approved and
 * continue from the checkpoint.
 *
 * <p>The default factories ({@link #requireFor}, {@link #when}) automatically
 * bypass the gate when the approval marker for that node is present in the
 * context's {@link StateKey} {@link #APPROVED_KEY}. Custom implementations
 * are free to honour or ignore the marker as they see fit.
 */
@FunctionalInterface
public interface ApprovalGate {

    /** Internal state key carrying the set of approved node names. */
    @SuppressWarnings("unchecked")
    StateKey<Set<String>> APPROVED_KEY =
            (StateKey<Set<String>>) (StateKey<?>) StateKey.of("__approval.granted", Set.class);

    Decision check(String nodeName, AgentContext context);

    /** No gate. Every node runs without approval. */
    ApprovalGate NONE = (node, ctx) -> Decision.allow();

    /**
     * Require approval for any of the listed node names. The gate honours
     * the approval marker — once a node has been approved for the run, it
     * runs without a second prompt.
     */
    static ApprovalGate requireFor(String... nodeNames) {
        Set<String> set = new HashSet<>(Arrays.asList(Objects.requireNonNull(nodeNames, "nodeNames")));
        return (node, ctx) -> {
            if (!set.contains(node) || isApproved(ctx, node)) {
                return Decision.allow();
            }
            return Decision.require("node '" + node + "' requires human approval");
        };
    }

    /**
     * Custom rule on {@code (node, context)}. {@code predicate} returning
     * {@code true} triggers an approval request; the marker bypass still
     * applies on top.
     */
    static ApprovalGate when(BiPredicate<String, AgentContext> predicate, String reason) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(reason, "reason");
        return (node, ctx) -> {
            if (isApproved(ctx, node) || !predicate.test(node, ctx)) {
                return Decision.allow();
            }
            return Decision.require(reason);
        };
    }

    /**
     * Compose two gates — both must allow for the node to run. If either
     * requires approval the call is paused; if both do, the first reason
     * wins.
     */
    default ApprovalGate and(ApprovalGate other) {
        Objects.requireNonNull(other, "other");
        return (node, ctx) -> {
            Decision first = this.check(node, ctx);
            return first.requiresApproval() ? first : other.check(node, ctx);
        };
    }

    /** Reads the approval marker for {@code nodeName} from {@code context}. */
    static boolean isApproved(AgentContext context, String nodeName) {
        Set<String> approved = context.get(APPROVED_KEY);
        return approved != null && approved.contains(nodeName);
    }

    /** Possible outcomes of {@link #check}. */
    enum Outcome { ALLOW, REQUIRE_APPROVAL }

    /** Result of a gate evaluation. */
    record Decision(Outcome outcome, @Nullable String reason) {

        public static Decision allow() {
            return new Decision(Outcome.ALLOW, null);
        }

        public static Decision require(String reason) {
            return new Decision(Outcome.REQUIRE_APPROVAL, Objects.requireNonNull(reason, "reason"));
        }

        public boolean requiresApproval() {
            return outcome == Outcome.REQUIRE_APPROVAL;
        }
    }
}
