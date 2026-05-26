package io.github.datallmhub.agentflow4j.squad;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.graph.BudgetLimits;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import io.github.datallmhub.agentflow4j.graph.CostMeter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetAwareRouterTests {

    private static final Set<String> BOTH = Set.of("premium", "fallback");

    // ----- Construction / validation -------------------------------------

    @Test
    void rejectsNegativeThreshold() {
        assertThatThrownBy(() -> new BudgetAwareRouter(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN, -0.01, "premium", "fallback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold must be >= 0");
    }

    @Test
    void rejectsNaNThreshold() {
        assertThatThrownBy(() -> new BudgetAwareRouter(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN, Double.NaN, "premium", "fallback"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIdenticalPremiumAndFallback() {
        assertThatThrownBy(() -> new BudgetAwareRouter(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN, 1.0, "same", "same"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    // ----- Decision: in-budget vs exhausted ------------------------------

    @Test
    void routesToPremiumWhenBudgetIsHealthy() {
        BudgetPolicy budget = newRunBudget(/* limit */ 5.00);
        // 0 spent → remaining = 5.00 ≥ threshold 1.00
        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 1.00, "premium", "fallback");

        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");
    }

    @Test
    void routesToFallbackWhenBudgetDropsBelowThreshold() {
        BudgetPolicy budget = newRunBudget(5.00);
        spend(budget, "premium", 4.50); // remaining = 0.50 < 1.00

        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 1.00, "premium", "fallback");

        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("fallback");
    }

    @Test
    void thresholdComparisonIsStrictlyLess() {
        // remaining EXACTLY equals threshold → still premium (strictly less switches)
        BudgetPolicy budget = newRunBudget(5.00);
        spend(budget, "premium", 4.00); // remaining = 1.00, threshold = 1.00

        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 1.00, "premium", "fallback");

        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");
    }

    @Test
    void noopBudgetAlwaysPremium() {
        // POSITIVE_INFINITY remaining → router never falls back
        RoutingStrategy router = RoutingStrategy.budgetAware(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN,
                Double.MAX_VALUE / 2, "premium", "fallback");

        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");
    }

    @Test
    void zeroThresholdOnlyFallsBackWhenBudgetIsNegativeOrExhausted() {
        BudgetPolicy budget = newRunBudget(5.00);
        spend(budget, "premium", 5.00); // remaining = 0.00, threshold = 0.00 → not less → premium

        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 0.00, "premium", "fallback");

        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");
    }

    // ----- Live behaviour: routing shifts as spend accumulates -----------

    @Test
    void switchesFromPremiumToFallbackAsSpendAccumulates() {
        BudgetPolicy budget = newRunBudget(2.00);
        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, 0.50, "premium", "fallback");

        // Start: 0 spent, remaining = 2.00 → premium
        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");

        spend(budget, "premium", 1.00); // remaining = 1.00 ≥ 0.50 → still premium
        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("premium");

        spend(budget, "premium", 0.60); // remaining = 0.40 < 0.50 → fallback
        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("fallback");
    }

    @Test
    void nodeScopeThresholdConsultsPerNodeSpend() {
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.builder().perNode(1.00).build(),
                (node, ctx) -> 0.0,
                CostMeter.perCall());

        // Spend 0.80 on the 'premium' node specifically.
        for (int i = 0; i < 8; i++) {
            budget.record("premium", AgentResult.ofText("ok"));
        }
        // Remaining for 'premium' at NODE = 1.00 - 0.80 = 0.20 < 0.50 → fallback
        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.NODE, 0.50, "premium", "fallback");
        assertThat(router.selectExecutor(AgentContext.empty(), BOTH)).isEqualTo("fallback");
    }

    // ----- Missing executors --------------------------------------------

    @Test
    void throwsWhenPremiumIsNotAvailable() {
        RoutingStrategy router = RoutingStrategy.budgetAware(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN, 1.0, "premium", "fallback");
        assertThatThrownBy(() ->
                router.selectExecutor(AgentContext.empty(), Set.of("fallback")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Premium executor not registered");
    }

    @Test
    void throwsWhenFallbackIsNotAvailable() {
        RoutingStrategy router = RoutingStrategy.budgetAware(
                BudgetPolicy.NOOP, BudgetPolicy.Scope.RUN, 1.0, "premium", "fallback");
        assertThatThrownBy(() ->
                router.selectExecutor(AgentContext.empty(), Set.of("premium")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fallback executor not registered");
    }

    // ----- BudgetPolicy.remaining contract -------------------------------

    @Test
    void hierarchicalRemainingClampsAtZeroAfterOverspend() {
        BudgetPolicy budget = newRunBudget(1.00);
        spend(budget, "any", 1.50); // overshoot
        assertThat(budget.remaining(BudgetPolicy.Scope.RUN, "any")).isEqualTo(0.0);
    }

    @Test
    void hierarchicalRemainingReturnsInfinityForUnboundedScope() {
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.run(1.00),   // perRun bounded, perNode + perCall unbounded
                (node, ctx) -> 0.0,
                CostMeter.perCall());
        assertThat(budget.remaining(BudgetPolicy.Scope.NODE, "x"))
                .isEqualTo(Double.POSITIVE_INFINITY);
        assertThat(budget.remaining(BudgetPolicy.Scope.CALL, "x"))
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void customBudgetPolicyDefaultsRemainingToInfinity() {
        BudgetPolicy custom = new BudgetPolicy() {
            @Override public Decision check(String node, AgentContext ctx) { return Decision.allow(); }
            @Override public void record(String node, AgentResult result) {}
            @Override public double spent(Scope scope, String node) { return 0; }
        };
        assertThat(custom.remaining(BudgetPolicy.Scope.RUN, "x"))
                .isEqualTo(Double.POSITIVE_INFINITY);
    }

    // ----- Helpers -------------------------------------------------------

    /** Builds a RUN-bounded hierarchical budget using a unit-cost meter. */
    private static BudgetPolicy newRunBudget(double perRun) {
        return BudgetPolicy.hierarchical(
                BudgetLimits.run(perRun),
                /* estimator */ (node, ctx) -> 0.0,
                /* meter     */ new FixedCostMeter());
    }

    /** Forces a known cost to be recorded against {@code node}. */
    private static void spend(BudgetPolicy budget, String node, double amount) {
        FixedCostMeter.NEXT_COST.set(amount);
        budget.record(node, AgentResult.ofText("done"));
        FixedCostMeter.NEXT_COST.set(0.0);
    }

    /** Test {@link CostMeter} that returns whatever the latest call set. */
    private static final class FixedCostMeter implements CostMeter {
        static final AtomicReference<Double> NEXT_COST = new AtomicReference<>(0.0);
        @Override public double measure(String nodeName, AgentResult result) {
            return NEXT_COST.get();
        }
    }
}
