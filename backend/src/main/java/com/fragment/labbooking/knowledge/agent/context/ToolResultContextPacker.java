package com.fragment.labbooking.knowledge.agent.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps tool results small enough for the next model round. */
@Component
public class ToolResultContextPacker {

    private static final int MAX_LIST_ITEMS = 6;
    private static final int MIN_VALUE_BUDGET = 48;

    private final ContextTokenCounter tokenCounter;
    private final ModelContextProfileProperties modelContextProfiles;

    public ToolResultContextPacker(ContextTokenCounter tokenCounter, ModelContextProfileProperties modelContextProfiles) {
        this.tokenCounter = tokenCounter;
        this.modelContextProfiles = modelContextProfiles;
    }

    public PackedToolResult pack(String toolName, Map<String, Object> rawOutput) {
        Map<String, Object> safeRaw = rawOutput == null ? Map.of() : rawOutput;
        int originalTokens = estimate(safeRaw);
        int maxToolResultTokens = modelContextProfiles.active().effectiveMaxSingleToolResultTokens();
        if (originalTokens <= maxToolResultTokens) {
            return new PackedToolResult(safeRaw, detail("DIRECT", originalTokens, originalTokens, 0));
        }

        Map<String, Object> packed = packMap(safeRaw, maxToolResultTokens);
        int packedTokens = estimate(packed);
        int droppedItems = countDroppedItems(safeRaw, packed);
        Map<String, Object> annotated = new LinkedHashMap<>(packed);
        annotated.put("_context_pack", Map.of(
                "strategy", "DETERMINISTIC_FIELD_AND_TOKEN_TRIM",
                "original_token_estimate", originalTokens,
                "packed_token_estimate", packedTokens,
                "dropped_item_count", droppedItems,
                "detail_available", "重新调用同一受权限控制的工具并缩小查询范围"));
        return new PackedToolResult(annotated,
                detail("DETERMINISTIC_FIELD_AND_TOKEN_TRIM", originalTokens, packedTokens, droppedItems));
    }

    private Map<String, Object> packMap(Map<String, Object> raw, int budget) {
        Map<String, Object> packed = new LinkedHashMap<>();
        int remaining = Math.max(MIN_VALUE_BUDGET, budget);
        int fieldsRemaining = Math.max(1, raw.size());
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            int fieldBudget = Math.max(MIN_VALUE_BUDGET, remaining / fieldsRemaining);
            Object value = packValue(entry.getValue(), fieldBudget, 0);
            packed.put(entry.getKey(), value);
            remaining = Math.max(0, remaining - estimate(value));
            fieldsRemaining--;
        }
        return packed;
    }

    private Object packValue(Object value, int budget, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return truncate(text, budget);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return depth >= 2 ? truncate(String.valueOf(normalized), budget) : packMap(normalized, budget);
        }
        if (value instanceof List<?> list) {
            int kept = Math.min(MAX_LIST_ITEMS, list.size());
            List<Object> packed = new ArrayList<>(kept + 1);
            int itemBudget = Math.max(MIN_VALUE_BUDGET, budget / Math.max(1, kept));
            for (int index = 0; index < kept; index++) {
                packed.add(packValue(list.get(index), itemBudget, depth + 1));
            }
            if (list.size() > kept) {
                packed.add(Map.of("_truncated_items", list.size() - kept));
            }
            return packed;
        }
        return truncate(String.valueOf(value), budget);
    }

    private String truncate(String value, int budget) {
        if (value == null || estimate(value) <= budget) {
            return value;
        }
        int low = 0;
        int high = value.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            if (estimate(value.substring(0, middle)) <= budget) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return value.substring(0, Math.max(0, low)) + "…[已按上下文预算截断]";
    }

    private int estimate(Object value) {
        return tokenCounter.estimate(String.valueOf(value));
    }

    private Map<String, Object> detail(String strategy, int originalTokens, int packedTokens, int droppedItems) {
        return Map.of(
                "strategy", strategy,
                "original_token_estimate", originalTokens,
                "packed_token_estimate", packedTokens,
                "dropped_item_count", droppedItems
        );
    }

    private int countDroppedItems(Map<String, Object> raw, Map<String, Object> packed) {
        int total = 0;
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            Object original = entry.getValue();
            Object normalized = packed.get(entry.getKey());
            if (original instanceof List<?> originalList && normalized instanceof List<?> packedList) {
                total += Math.max(0, originalList.size() - packedList.size());
            }
        }
        return total;
    }

    public record PackedToolResult(Map<String, Object> modelOutput, Map<String, Object> safeDetail) {
    }
}
