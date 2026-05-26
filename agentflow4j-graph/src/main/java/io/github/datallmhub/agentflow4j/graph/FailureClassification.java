package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of classifying a node failure: its {@link FailureCategory}, an
 * optional retry hint provided by the failure itself (e.g. a {@code Retry-After}
 * header on an HTTP 429 response), and an optional human-readable reason.
 *
 * <p>Instances are produced by a {@link FailureClassifier} and consumed by
 * the graph's retry layer. Use the static factories for the common cases.
 */
public record FailureClassification(
        FailureCategory category,
        @Nullable Duration retryAfter,
        @Nullable String reason) {

    public FailureClassification {
        Objects.requireNonNull(category, "category");
        if (retryAfter != null && retryAfter.isNegative()) {
            throw new IllegalArgumentException("retryAfter must be non-negative");
        }
    }

    /** Transient failure, retry with the policy's computed backoff. */
    public static FailureClassification transientFailure() {
        return new FailureClassification(FailureCategory.TRANSIENT, null, null);
    }

    /** Transient failure with an explicit retry delay (e.g. {@code Retry-After}). */
    public static FailureClassification transientFailure(Duration retryAfter) {
        return new FailureClassification(FailureCategory.TRANSIENT, retryAfter, null);
    }

    /** Permanent failure — the same call would fail the same way. */
    public static FailureClassification permanent() {
        return new FailureClassification(FailureCategory.PERMANENT, null, null);
    }

    /** Permanent failure with a reason recorded in logs and the audit trail. */
    public static FailureClassification permanent(String reason) {
        return new FailureClassification(FailureCategory.PERMANENT, null, reason);
    }

    /** Budget exhaustion — the graph emits an {@link InterruptRequest} for this. */
    public static FailureClassification overBudget(String reason) {
        return new FailureClassification(FailureCategory.OVER_BUDGET, null, reason);
    }
}
