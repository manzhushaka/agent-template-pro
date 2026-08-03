package com.manzhushaka.agent.runtime.workflow;

import java.util.Locale;
import java.util.Map;

/**
 * Deterministic condition evaluator for workflow edges. Supports:
 * {@code default}, {@code true}, {@code false}, {@code $var}, {@code $var == 'x'},
 * {@code $var != 'x'}, {@code $var contains 'x'} and dotted paths like {@code $a.b}.
 */
public final class WorkflowCondition {
    private WorkflowCondition() {
    }

    public static boolean evaluate(String condition, Map<String, Object> variables) {
        String expression = condition == null ? null : condition.trim();
        if (expression == null || "default".equals(expression) || expression.isEmpty()) {
            return false;
        }
        if ("true".equals(expression)) {
            return true;
        }
        if ("false".equals(expression)) {
            return false;
        }
        if (expression.startsWith("$")) {
            int operatorIndex = firstOperator(expression);
            if (operatorIndex < 0) {
                return truthy(resolve(expression.substring(1), variables));
            }
            String path = expression.substring(1, operatorIndex).trim();
            String operator = expression.substring(operatorIndex).trim();
            String op;
            String right;
            if (operator.startsWith("==")) {
                op = "==";
                right = operator.substring(2).trim();
            } else if (operator.startsWith("!=")) {
                op = "!=";
                right = operator.substring(2).trim();
            } else if (operator.startsWith("contains")) {
                op = "contains";
                right = operator.substring(8).trim();
            } else {
                return false;
            }
            Object left = resolve(path, variables);
            Object rightValue = literal(right);
            return switch (op.toLowerCase(Locale.ROOT)) {
                case "==" -> equalsValue(left, rightValue);
                case "!=" -> !equalsValue(left, rightValue);
                case "contains" -> String.valueOf(left).contains(String.valueOf(rightValue));
                default -> false;
            };
        }
        return false;
    }

    private static int firstOperator(String expression) {
        int index = expression.indexOf("==");
        int notEquals = expression.indexOf("!=");
        int contains = expression.indexOf("contains");
        int best = -1;
        for (int candidate : new int[]{index, notEquals, contains}) {
            if (candidate >= 0 && (best < 0 || candidate < best)) {
                best = candidate;
            }
        }
        return best;
    }

    private static Object resolve(String path, Map<String, Object> variables) {
        String[] parts = path.split("\\.");
        Object current = variables;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }
        return current;
    }

    private static Object literal(String raw) {
        if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        try {
            return Double.valueOf(raw);
        } catch (NumberFormatException ignored) {
            return raw;
        }
    }

    private static boolean equalsValue(Object left, Object right) {
        if (left == null) {
            return right == null;
        }
        if (left instanceof Number number && right instanceof Number other) {
            return Double.compare(number.doubleValue(), other.doubleValue()) == 0;
        }
        return left.equals(right);
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        return !String.valueOf(value).isBlank();
    }
}
