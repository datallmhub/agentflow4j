package io.github.datallmhub.agentflow4j.checkpoint;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.datallmhub.agentflow4j.core.StateKey;
import io.github.datallmhub.agentflow4j.graph.ApprovalGate;

public final class StateTypeRegistry {

    private final Map<String, Class<?>> byLogicalName = new HashMap<>();
    private final Map<Class<?>, String> byClass = new HashMap<>();

    /**
     * Pre-registers the framework's own state keys, so that a run resumed via
     * an {@link ApprovalGate} can be checkpointed again without extra setup.
     */
    public StateTypeRegistry() {
        register(ApprovalGate.APPROVED_KEY);
    }

    public <T> StateTypeRegistry register(String logicalName, Class<T> type) {
        Objects.requireNonNull(logicalName, "logicalName");
        Objects.requireNonNull(type, "type");
        if (logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName must not be blank");
        }
        Class<?> existing = byLogicalName.putIfAbsent(logicalName, type);
        if (existing != null && !existing.equals(type)) {
            throw new IllegalStateException("Logical name '" + logicalName
                    + "' already bound to " + existing.getName());
        }
        byClass.putIfAbsent(type, logicalName);
        return this;
    }

    public <T> StateTypeRegistry register(StateKey<T> key) {
        return register(key.name(), key.type());
    }

    public Optional<Class<?>> resolve(String logicalName) {
        return Optional.ofNullable(byLogicalName.get(logicalName));
    }

    public Optional<String> logicalNameOf(Class<?> type) {
        return Optional.ofNullable(byClass.get(type));
    }

    public boolean isRegistered(Class<?> type) {
        return byClass.containsKey(type);
    }
}
