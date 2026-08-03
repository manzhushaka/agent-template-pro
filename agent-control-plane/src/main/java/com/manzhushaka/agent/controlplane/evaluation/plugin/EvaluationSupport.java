package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.common.mask.SensitiveMasker;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared deterministic helpers for built-in evaluators. */
final class EvaluationSupport {
    private EvaluationSupport() { }

    static String text(Map<String, Object> values, String key) {
        Object value = values == null ? null : values.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    static boolean matches(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        return actual.trim().equalsIgnoreCase(expected.trim());
    }

    static boolean containsAllIgnoreCase(List<String> actual, Object expectedValue) {
        if (!(expectedValue instanceof List<?> expectedList)) {
            return false;
        }
        List<String> expected = expectedList.stream().map(String::valueOf).map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        List<String> actualValues = actual.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        return actualValues.containsAll(expected);
    }

    static boolean textClean(String text) {
        return text == null || text.isBlank() || SensitiveMasker.isClean(text);
    }
}
