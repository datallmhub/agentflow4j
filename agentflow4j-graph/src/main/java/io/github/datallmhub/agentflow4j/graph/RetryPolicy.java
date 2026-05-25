package io.github.datallmhub.agentflow4j.graph;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public record RetryPolicy(
        int maxAttempts,
        Duration baseDelay,
        Duration maxDelay,
        double backoffMultiplier,
        double jitterFactor,
        Predicate<Throwable> retryOn,
        FailureClassifier classifier) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(baseDelay, "baseDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        Objects.requireNonNull(retryOn, "retryOn");
        Objects.requireNonNull(classifier, "classifier");
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must be non-negative");
        }
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be >= baseDelay");
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0");
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0]");
        }
    }

    /**
     * Backward-compatible six-argument constructor — delegates with
     * {@link FailureClassifier#defaults()}.
     */
    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay,
                       double backoffMultiplier, double jitterFactor,
                       Predicate<Throwable> retryOn) {
        this(maxAttempts, baseDelay, maxDelay, backoffMultiplier, jitterFactor, retryOn,
                FailureClassifier.defaults());
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                RetryPredicates.never(), FailureClassifier.defaults());
    }

    public static RetryPolicy once() {
        return new RetryPolicy(2, Duration.ZERO, Duration.ZERO, 1.0, 0.0,
                RetryPredicates.always(), FailureClassifier.defaults());
    }

    public static RetryPolicy exponential(int maxAttempts, Duration baseDelay) {
        return new RetryPolicy(maxAttempts, baseDelay, baseDelay.multipliedBy(32),
                2.0, 0.2, RetryPredicates.transientIo(), FailureClassifier.defaults());
    }

    /**
     * Classify a failure using this policy's {@link FailureClassifier},
     * combined with the legacy {@link #retryOn() retryOn} predicate.
     *
     * <p>The classifier is consulted first. If it returns {@code null}
     * (declines to decide), the {@code retryOn} predicate is used as a
     * fallback: {@code true} → {@link FailureCategory#TRANSIENT},
     * {@code false} → {@link FailureCategory#PERMANENT}. This keeps callers
     * that only set {@code retryOn} working unchanged while letting new
     * callers use the richer classifier API.
     */
    public FailureClassification classify(Throwable cause) {
        FailureClassification c = classifier.classify(cause);
        if (c != null) {
            return c;
        }
        return retryOn.test(cause)
                ? FailureClassification.transientFailure()
                : FailureClassification.permanent();
    }

    /** Returns a copy of this policy with the given classifier installed. */
    public RetryPolicy withClassifier(FailureClassifier other) {
        Objects.requireNonNull(other, "classifier");
        return new RetryPolicy(maxAttempts, baseDelay, maxDelay,
                backoffMultiplier, jitterFactor, retryOn, other);
    }

    public long computeDelayMs(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
        double raw = baseDelay.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 1);
        long cap = Math.min((long) raw, maxDelay.toMillis());
        if (cap <= 0 || jitterFactor == 0.0) {
            return Math.max(0L, cap);
        }
        long jitterWindow = (long) (cap * jitterFactor);
        long floor = cap - jitterWindow;
        return floor + ThreadLocalRandom.current().nextLong(jitterWindow + 1);
    }
}
