package io.github.datallmhub.agentflow4j.samples;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.graph.BudgetLimits;
import io.github.datallmhub.agentflow4j.graph.BudgetPolicy;
import io.github.datallmhub.agentflow4j.graph.CostMeter;
import io.github.datallmhub.agentflow4j.squad.CoordinatorAgent;
import io.github.datallmhub.agentflow4j.squad.RoutingStrategy;

/**
 * 06 — Budget-aware routing.
 *
 * <p>Two agents are wired behind a {@link CoordinatorAgent}: a <em>premium</em>
 * one that simulates an expensive call ($0.50 / call) and a cheap
 * <em>fallback</em> ($0.02 / call). The router consults the live
 * {@link BudgetPolicy} and, once the remaining run budget drops below the
 * configured threshold, switches every subsequent call to the fallback —
 * deterministically and without paying for any classification.
 *
 * <p>Expected output: the first few calls use the premium agent, then the
 * router silently switches to the fallback as the budget runs down.
 */
public final class BudgetAwareRoutingDemo {

    public static void main(String[] args) {
        System.out.println("=== Budget-Aware Routing ===\n");

        // Two executors, very different cost profiles.
        Agent premium = ctx -> {
            System.out.println("  [premium]  expensive call (~$0.50)");
            return AgentResult.ofText("premium answer");
        };
        Agent fallback = ctx -> {
            System.out.println("  [fallback] cheap call (~$0.02)");
            return AgentResult.ofText("fallback answer");
        };

        // A unit-cost meter that returns the latest configured cost. The
        // executors above don't actually know how much they "cost" — in a
        // real wiring this would come from AgentResult.usage() via a
        // CostMeter implementation that maps tokens to dollars.
        BillingMeter meter = new BillingMeter();

        // RUN budget = $2.00. Switch to fallback once less than $0.40 remains.
        BudgetPolicy budget = BudgetPolicy.hierarchical(
                BudgetLimits.builder().perRun(2.00).build(),
                /* estimator: not used by this demo */ (node, ctx) -> 0.0,
                meter);

        RoutingStrategy router = RoutingStrategy.budgetAware(
                budget, BudgetPolicy.Scope.RUN, /* threshold */ 0.40,
                "premium", "fallback");

        CoordinatorAgent coordinator = CoordinatorAgent.builder()
                .executor("premium", premium)
                .executor("fallback", fallback)
                .routingStrategy(router)
                .build();

        // Six requests; pricing per request alternates based on which executor
        // the router picks. We record cost manually after each call to
        // simulate the graph's "record after attempt" flow without a full
        // AgentGraph wiring (kept simple for the demo).
        for (int i = 1; i <= 6; i++) {
            System.out.printf("Request #%d (remaining: $%.2f)%n", i,
                    budget.remaining(BudgetPolicy.Scope.RUN, "premium"));
            AgentResult result = coordinator.execute(
                    AgentContext.of("answer me " + i));
            // Charge the cost matching the executor that ran.
            double cost = result.text().startsWith("premium") ? 0.50 : 0.02;
            meter.nextCost(cost);
            budget.record(/* nodeName */ "any", result);
            System.out.println();
        }

        System.out.printf("Final remaining: $%.2f%n",
                budget.remaining(BudgetPolicy.Scope.RUN, "premium"));
    }

    /** Test-only meter — returns the cost set by the last {@code nextCost(...)} call. */
    private static final class BillingMeter implements CostMeter {
        private double next = 0.0;
        void nextCost(double v) { this.next = v; }
        @Override public double measure(String nodeName, AgentResult result) { return next; }
    }
}
