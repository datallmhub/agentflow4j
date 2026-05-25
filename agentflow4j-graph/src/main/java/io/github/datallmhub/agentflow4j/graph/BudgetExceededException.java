package io.github.datallmhub.agentflow4j.graph;

/**
 * Thrown from inside a node when it detects a budget exhaustion that the
 * framework's {@link BudgetPolicy} could not gate beforehand — e.g. an LLM
 * provider returned an HTTP 402 / "insufficient quota" payload, or the node's
 * own bookkeeping says it would exceed a per-node ceiling.
 *
 * <p>The {@link FailureClassifier#defaults() default classifier} maps this
 * exception to {@link FailureCategory#OVER_BUDGET}, which causes the graph
 * to emit an {@link InterruptRequest} (reason {@code budget.exceeded:<node>})
 * rather than retrying — retrying a budget condition just burns more money.
 *
 * <p>Use it for failures detected <em>during</em> execution. Failures detected
 * <em>before</em> execution are handled by {@link BudgetPolicy#check} via the
 * existing gate path; this exception is the runtime counterpart for cases the
 * gate could not anticipate.
 */
public class BudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BudgetExceededException(String message) {
        super(message);
    }

    public BudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
