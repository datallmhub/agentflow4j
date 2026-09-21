package io.github.datallmhub.agentflow4j.autoconfigure;

import io.github.datallmhub.agentflow4j.core.Experimental;
import io.github.datallmhub.agentflow4j.graph.ErrorPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agentflow4j")
public class AgentFlow4jProperties {

    private boolean enabled = true;

    private ErrorPolicy defaultErrorPolicy = ErrorPolicy.FAIL_FAST;

    private final Observability observability = new Observability();

    private final Squad squad = new Squad();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ErrorPolicy getDefaultErrorPolicy() {
        return defaultErrorPolicy;
    }

    public void setDefaultErrorPolicy(ErrorPolicy defaultErrorPolicy) {
        this.defaultErrorPolicy = defaultErrorPolicy;
    }

    public Observability getObservability() {
        return observability;
    }

    public Squad getSquad() {
        return squad;
    }

    public static class Observability {
        private boolean metrics = true;
        private boolean events = true;

        public boolean isMetrics() { return metrics; }
        public void setMetrics(boolean metrics) { this.metrics = metrics; }
        public boolean isEvents() { return events; }
        public void setEvents(boolean events) { this.events = events; }
    }

    @Experimental
    public static class Squad {
        public enum DefaultRoutingStrategy { FIRST, LLM_DRIVEN }

        private DefaultRoutingStrategy defaultRoutingStrategy = DefaultRoutingStrategy.FIRST;

        public DefaultRoutingStrategy getDefaultRoutingStrategy() { return defaultRoutingStrategy; }
        public void setDefaultRoutingStrategy(DefaultRoutingStrategy s) { this.defaultRoutingStrategy = s; }
    }
}
