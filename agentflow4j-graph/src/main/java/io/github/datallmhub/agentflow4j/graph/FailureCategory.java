package io.github.datallmhub.agentflow4j.graph;

/**
 * Why a node attempt failed, from the retry layer's point of view.
 *
 * <p>The categories are deliberately minimal — three cover ~95% of real cases
 * and keep the retry decision tree readable:
 *
 * <ul>
 *   <li>{@link #TRANSIENT} — retry with backoff (or with the delay provided
 *       by the failure itself, e.g. a {@code Retry-After} header).</li>
 *   <li>{@link #PERMANENT} — do not retry; the next attempt would fail the
 *       same way and just burn tokens.</li>
 *   <li>{@link #OVER_BUDGET} — the failure indicates a budget exhaustion;
 *       the graph emits an {@link InterruptRequest} so the caller can pause,
 *       alert, raise the limit, and resume rather than crashing.</li>
 * </ul>
 *
 * @see FailureClassifier
 * @see FailureClassification
 */
public enum FailureCategory {
    TRANSIENT,
    PERMANENT,
    OVER_BUDGET
}
