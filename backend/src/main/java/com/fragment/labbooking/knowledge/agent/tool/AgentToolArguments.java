package com.fragment.labbooking.knowledge.agent.tool;

import com.fragment.labbooking.common.exception.BusinessException;

import java.util.List;

final class AgentToolArguments {

    private AgentToolArguments() {
    }

    static String optionalText(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text.substring(0, Math.min(text.length(), maxLength));
    }

    static Long requiredPositiveLong(Object value, String field) {
        try {
            long number = value instanceof Number numberValue ? numberValue.longValue()
                    : Long.parseLong(String.valueOf(value));
            if (number <= 0) {
                throw new NumberFormatException();
            }
            return number;
        } catch (Exception exception) {
            throw new BusinessException("工具参数 " + field + " 必须是正整数");
        }
    }

    static List<String> requiredStringList(Object value, String field) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new BusinessException("工具参数 " + field + " 必须是非空数组");
        }
        List<String> normalized = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(text -> !text.isEmpty())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new BusinessException("工具参数 " + field + " 必须是非空数组");
        }
        return normalized;
    }
}
