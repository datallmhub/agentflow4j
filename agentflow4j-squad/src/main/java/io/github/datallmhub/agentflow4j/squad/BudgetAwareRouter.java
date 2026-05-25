package io.github.datallmhub.agentflow4j.squad;

import java.util.Objects;
import java.util.Set;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;

/**
 * Deterministic, cost-aware {@link RoutingStrategy} that routes to a
 * <em>premium</em> agent while there is budget remaining, and switches to a
 * cheaper <em>fallback</em> agent once the remaining budget at a configured
 * {@link BudgetPolicy.Scope scope} falls below a threshold.
 *
 * <p>This is the one cost-aware routing lever that is both <em>deterministic</em>
 * and <em>provably cheaper</em>: classifying complexity ex-ante via an LLM
 * would already cost a call (chicken-and-egg), and self-confidence routing
 * doubles the cost. Reading the live {@link BudgetPolicy} counters is free.
 *
 * <p>Typical wiring:
 *
 * <pre>{@code
 * BudgetPolicy budget = BudgetPolicy.hierarchical(
 *         BudgetLimits.builder().perRun(5.00).build(),
 *         estimator, meter);
 *
 * RoutingStrategy router = RoutingStrategy.budgetAware(
 *         budget, BudgetPolicy.Scope.RUN, 1.00, "premium", "fallback");
 *
 * // The graph and the router share the same BudgetPolicy instance so the
 * // router sees live spend.
 * }</pre>
 */
public final class BudgetAwareRouter implements RoutingStrategy {

    private final BudgetPolicy budgetPolicy;
    private final BudgetPolicy.Scope scope;
    private final double threshold;
    private final String premiumExecutor;
    private final String fallbackExecutor;

    /**
     * @param budgetPolicy     the live policy whose remaining counters drive
     *                         the routing decision; must be the same instance
     *                         wired into the graph.
     * @param scope            the scope to consult — typically
     *                         {@link BudgetPolicy.Scope#RUN}.
     * @param threshold        switch to {@code fallbackExecutor} once
     *                         {@code remaining < threshold} (strictly less);
     *                         must be {@code >= 0}.
     * @param premiumExecutor  the executor name to prefer while budget allows.
     * @param fallbackExecutor the executor name to use once the threshold is crossed.
     */
    public BudgetAwareRouter(BudgetPolicy budgetPolicy,
                             BudgetPolicy.Scope scope,
                             double threshold,
                             String premiumExecutor,
                             String fallbackExecutor) {
        this.budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
        this.scope = Objects.requireNonNull(scope, "scope");
        if (threshold < 0.0 || Double.isNaN(threshold)) {
            throw new IllegalArgumentException("threshold must be >= 0 (was " + threshold + ")");
        }
        this.threshold = threshold;
        this.premiumExecutor = Objects.requireNonNull(premiumExecutor, "premiumExecutor");
        this.fallbackExecutor = Objects.requireNonNull(fallbackExecutor, "fallbackExecutor");
        if (premiumExecutor.equals(fallbackExecutor)) {
            throw new IllegalArgumentException(
                    "premiumExecutor and fallbackExecutor must differ (was both '"
                            + premiumExecutor + "')");
        }
    }

    @Override
    public String selectExecutor(AgentContext context, Set<String> availableExecutors) {
        if (!availableExecutors.contains(premiumExecutor)) {
            throw new IllegalStateException(
                    "Premium executor not registered: '" + premiumExecutor + "'");
        }
        if (!availableExecutors.contains(fallbackExecutor)) {
            throw new IllegalStateException(
                    "Fallback executor not registered: '" + fallbackExecutor + "'");
        }
        double remaining = budgetPolicy.remaining(scope, premiumExecutor);
        return remaining < threshold ? fallbackExecutor : premiumExecutor;
    }

    /** Exposed for tests and observability. */
    public double threshold() {
        return threshold;
    }

    /** Exposed for tests and observability. */
    public BudgetPolicy.Scope scope() {
        return scope;
    }

    /** Exposed for tests and observability. */
    public String premiumExecutor() {
        return premiumExecutor;
    }

    /** Exposed for tests and observability. */
    public String fallbackExecutor() {
        return fallbackExecutor;
    }
}
