package io.github.datallmhub.agentflow4j.graph;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.DoubleAdder;

import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentResult;

/**
 * Default {@link BudgetPolicy} implementation enforcing three hierarchical
 * tiers (run &gt; node &gt; call) with caller-supplied cost functions.
 *
 * <p>Counters use {@link DoubleAdder} for lock-free updates and are scoped
 * to this instance — create a new policy per graph run if isolation matters.
 */
public final class HierarchicalBudgetPolicy implements BudgetPolicy {

    private final BudgetLimits limits;
    private final CostEstimator estimator;
    private final CostMeter meter;
    private final DoubleAdder runSpent = new DoubleAdder();
    private final ConcurrentMap<String, DoubleAdder> nodeSpent = new ConcurrentHashMap<>();

    public HierarchicalBudgetPolicy(BudgetLimits limits, CostEstimator estimator, CostMeter meter) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.meter = Objects.requireNonNull(meter, "meter");
    }

    @Override
    public Decision check(String nodeName, AgentContext context) {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(context, "context");
        double estimate = estimator.estimate(nodeName, context);
        if (estimate < 0) {
            throw new IllegalStateException("CostEstimator returned negative value: " + estimate);
        }

        if (estimate > limits.perCall()) {
            return Decision.deny(Scope.CALL, nodeName, limits.perCall(), estimate);
        }
        double nodeProjected = currentNodeSpent(nodeName) + estimate;
        if (nodeProjected > limits.perNode()) {
            return Decision.deny(Scope.NODE, nodeName, limits.perNode(), nodeProjected);
        }
        double runProjected = runSpent.sum() + estimate;
        if (runProjected > limits.perRun()) {
            return Decision.deny(Scope.RUN, nodeName, limits.perRun(), runProjected);
        }
        return Decision.allow();
    }

    @Override
    public void record(String nodeName, AgentResult result) {
        Objects.requireNonNull(nodeName, "nodeName");
        Objects.requireNonNull(result, "result");
        double cost = meter.measure(nodeName, result);
        if (cost <= 0.0) {
            return;
        }
        runSpent.add(cost);
        nodeSpent.computeIfAbsent(nodeName, k -> new DoubleAdder()).add(cost);
    }

    @Override
    public double spent(Scope scope, String nodeName) {
        return switch (scope) {
            case RUN -> runSpent.sum();
            case NODE -> currentNodeSpent(nodeName);
            case CALL -> 0.0;
        };
    }

    @Override
    public double remaining(Scope scope, String nodeName) {
        double limit = switch (scope) {
            case RUN  -> limits.perRun();
            case NODE -> limits.perNode();
            case CALL -> limits.perCall();
        };
        if (Double.isInfinite(limit)) {
            return Double.POSITIVE_INFINITY;
        }
        double left = limit - spent(scope, nodeName);
        return left < 0.0 ? 0.0 : left;
    }

    public BudgetLimits limits() {
        return limits;
    }

    private double currentNodeSpent(String nodeName) {
        DoubleAdder adder = nodeSpent.get(nodeName);
        return adder == null ? 0.0 : adder.sum();
    }
}
