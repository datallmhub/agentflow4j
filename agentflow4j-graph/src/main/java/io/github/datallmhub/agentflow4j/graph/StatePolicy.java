package io.github.datallmhub.agentflow4j.graph;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import io.github.datallmhub.agentflow4j.core.StateKey;
import org.jspecify.annotations.Nullable;

/**
 * Allow / deny gate evaluated <em>before</em> a state mutation is applied
 * to {@code AgentContext}. Implementations are pure functions of
 * {@code (key, value)} so they can be unit-tested without booting the graph.
 *
 * <p>A denied mutation surfaces as a {@link StatePolicyViolation} thrown
 * inside the graph runtime, which the configured {@code ErrorPolicy}
 * then handles (FAIL_FAST, SKIP_NODE).
 *
 * <p>This SPI is write-only by design. Reads via {@link io.github.datallmhub.agentflow4j.core.AgentContext#get}
 * are not intercepted — most production governance needs are about
 * preventing unauthorised mutations to sensitive keys (e.g.
 * {@code payment.confirmed}, {@code user.refunded}), not blocking reads.
 */
@FunctionalInterface
public interface StatePolicy {

    Decision check(StateKey<?> key, @Nullable Object value);

    /** Every write allowed. Default; zero overhead in the graph runtime. */
    StatePolicy ALLOW_ALL = (key, value) -> Decision.allow();

    /** Every write denied. Useful for sanity-checking a flow runs read-only. */
    StatePolicy DENY_ALL = (key, value) -> Decision.deny("DENY_ALL policy");

    /**
     * Allow only the listed key names; deny everything else.
     */
    static StatePolicy allowWriteKeys(String... allowed) {
        Set<String> set = new HashSet<>(Arrays.asList(Objects.requireNonNull(allowed, "allowed")));
        return (key, value) -> set.contains(key.name())
                ? Decision.allow()
                : Decision.deny("key '" + key.name() + "' is not in the write allow-list");
    }

    /**
     * Deny the listed key names; allow everything else.
     */
    static StatePolicy denyWriteKeys(String... denied) {
        Set<String> set = new HashSet<>(Arrays.asList(Objects.requireNonNull(denied, "denied")));
        return (key, value) -> set.contains(key.name())
                ? Decision.deny("write to '" + key.name() + "' is denied")
                : Decision.allow();
    }

    /**
     * Custom rule on {@code (key, value)}. {@code predicate} returning
     * {@code true} allows the write.
     */
    static StatePolicy when(BiPredicate<StateKey<?>, Object> predicate, String denialReason) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(denialReason, "denialReason");
        return (key, value) -> predicate.test(key, value)
                ? Decision.allow()
                : Decision.deny(denialReason);
    }

    /**
     * Compose two policies — both must allow for the write to pass. The
     * first denial short-circuits.
     */
    default StatePolicy and(StatePolicy other) {
        Objects.requireNonNull(other, "other");
        return (key, value) -> {
            Decision first = this.check(key, value);
            return first.denied() ? first : other.check(key, value);
        };
    }

    /** Outcome of a {@link #check} call. */
    record Decision(boolean allowed, @Nullable String reason) {

        public static Decision allow() {
            return new Decision(true, null);
        }

        public static Decision deny(String reason) {
            return new Decision(false, Objects.requireNonNull(reason, "reason"));
        }

        public boolean denied() {
            return !allowed;
        }
    }
}
