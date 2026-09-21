package io.github.datallmhub.agentflow4j.squad;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.datallmhub.agentflow4j.core.Agent;
import io.github.datallmhub.agentflow4j.core.AgentContext;
import io.github.datallmhub.agentflow4j.core.AgentError;
import io.github.datallmhub.agentflow4j.core.AgentResult;
import io.github.datallmhub.agentflow4j.core.AgentUsage;
import io.github.datallmhub.agentflow4j.core.Experimental;

@Experimental
public final class ParallelAgent implements Agent {

    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    private final String name;
    private final Map<String, Agent> branches;
    private final Combiner combiner;
    private final Duration timeout;
    private final int maxConcurrency;

    private ParallelAgent(Builder b) {
        if (b.branches.isEmpty()) {
            throw new IllegalStateException("ParallelAgent requires at least one branch");
        }
        this.name = b.name;
        this.branches = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.branches));
        this.combiner = Objects.requireNonNull(b.combiner, "combiner");
        this.timeout = b.timeout;
        this.maxConcurrency = b.maxConcurrency;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String name() {
        return name;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        List<String> order = new ArrayList<>(branches.keySet());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(order.size(), maxConcurrency));
        try {
            List<CompletableFuture<AgentResult>> futures = new ArrayList<>(order.size());
            for (String branchName : order) {
                Agent branch = branches.get(branchName);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return branch.execute(context);
                    } catch (Throwable t) {
                        return AgentResult.failed(AgentError.of(branchName, t));
                    }
                }, pool));
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));
            try {
                if (timeout != null) {
                    all.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                } else {
                    all.join();
                }
            } catch (TimeoutException te) {
                return AgentResult.failed(AgentError.of(name, te));
            } catch (Exception e) {
                return AgentResult.failed(AgentError.of(name, e));
            }
            Map<String, AgentResult> ordered = new LinkedHashMap<>();
            for (int i = 0; i < order.size(); i++) {
                ordered.put(order.get(i), futures.get(i).join());
            }
            return combiner.combine(context, ordered);
        } finally {
            pool.shutdownNow();
        }
    }

    public interface Combiner {
        AgentResult combine(AgentContext context, Map<String, AgentResult> branchResults);
    }

    public static Combiner concatTexts(String separator) {
        Objects.requireNonNull(separator, "separator");
        return (ctx, results) -> {
            StringBuilder sb = new StringBuilder();
            AgentUsage totalUsage = null;
            boolean first = true;
            for (Map.Entry<String, AgentResult> e : results.entrySet()) {
                String text = e.getValue().text();
                if (text != null && !text.isEmpty()) {
                    if (!first) {
                        sb.append(separator);
                    }
                    sb.append(text);
                    first = false;
                }
                AgentUsage u = e.getValue().usage();
                if (u != null) {
                    totalUsage = totalUsage == null ? u : totalUsage.add(u);
                }
            }
            return AgentResult.builder()
                    .text(sb.toString())
                    .usage(totalUsage)
                    .completed(true)
                    .build();
        };
    }

    public static final class Builder {
        private String name = "parallel";
        private final Map<String, Agent> branches = new LinkedHashMap<>();
        private Combiner combiner = concatTexts("\n\n");
        private Duration timeout;
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            if (maxConcurrency < 1) {
                throw new IllegalArgumentException(
                        "maxConcurrency must be >= 1, got " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder branch(String branchName, Agent agent) {
            Objects.requireNonNull(branchName, "branchName");
            Objects.requireNonNull(agent, "agent");
            if (branches.putIfAbsent(branchName, agent) != null) {
                throw new IllegalStateException("Duplicate branch: " + branchName);
            }
            return this;
        }

        public Builder combiner(Combiner combiner) {
            this.combiner = Objects.requireNonNull(combiner, "combiner");
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public ParallelAgent build() {
            return new ParallelAgent(this);
        }
    }
}
