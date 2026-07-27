package com.fragment.labbooking.knowledge.agent.model;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

/** Shared normalization rules for immutable agent model values. */
public final class AgentModelGuard {

    private AgentModelGuard() {
    }

    public static String text(String value) {
        return value == null ? "" : value;
    }

    public static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    public static <K, V> Map<K, V> map(Map<K, V> value) {
        return value == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    public static Long requiredId(Long value, String name) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be a positive id");
        }
        return value;
    }
}
