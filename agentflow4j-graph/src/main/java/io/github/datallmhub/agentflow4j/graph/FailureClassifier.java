package io.github.datallmhub.agentflow4j.graph;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Decides how a node failure should be handled: retried, given up on, or
 * surfaced to the caller as an interrupt.
 *
 * <p>Classifiers compose with {@link #orElse(FailureClassifier)} — a custom
 * classifier delegates the cases it does not know about to the default:
 *
 * <pre>{@code
 * FailureClassifier customer = cause -> {
 *     if (cause instanceof MyDomainException) return FailureClassification.permanent("domain rule");
 *     return null; // unknown — let the next classifier decide
 * };
 * RetryPolicy policy = RetryPolicy.exponential(3, Duration.ofSeconds(1))
 *         .withClassifier(customer.orElse(FailureClassifier.defaults()));
 * }</pre>
 *
 * <p>Returning {@code null} from {@link #classify(Throwable)} means "I don't
 * know" and lets {@link #orElse(FailureClassifier)} — or the policy's legacy
 * {@code retryOn} predicate — decide instead.
 */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * Classify the failure. Returning {@code null} means the classifier
     * declines to decide; useful when composing via {@link #orElse}.
     */
    @Nullable
    FailureClassification classify(Throwable cause);

    /**
     * Compose with a fallback classifier. The fallback is consulted whenever
     * this classifier returns {@code null}.
     */
    default FailureClassifier orElse(FailureClassifier fallback) {
        Objects.requireNonNull(fallback, "fallback");
        return cause -> {
            FailureClassification self = classify(cause);
            return self != null ? self : fallback.classify(cause);
        };
    }

    /**
     * Default classifier: recognises common JDK transient I/O exceptions
     * ({@link java.io.IOException}, {@link java.util.concurrent.TimeoutException}),
     * Spring AI / Spring Web HTTP exceptions (5xx + {@code 429} as TRANSIENT —
     * honouring {@code Retry-After}; other 4xx as PERMANENT), and
     * {@link BudgetExceededException} as {@link FailureCategory#OVER_BUDGET}.
     * Everything else is {@link FailureCategory#PERMANENT}.
     *
     * <p>Detection of Spring exceptions is by class name to keep this module
     * free of any Spring dependency at compile time.
     */
    static FailureClassifier defaults() {
        return DefaultFailureClassifier.INSTANCE;
    }
}
