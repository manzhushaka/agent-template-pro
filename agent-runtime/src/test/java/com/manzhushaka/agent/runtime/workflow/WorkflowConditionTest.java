package com.manzhushaka.agent.runtime.workflow;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConditionTest {
    @Test
    void evaluatesEqualityAndDottedPaths() {
        Map<String, Object> variables = Map.of("room", Map.of("available", true));
        assertTrue(WorkflowCondition.evaluate("$room.available == true", variables));
        assertTrue(WorkflowCondition.evaluate("$room.available", variables));
        assertFalse(WorkflowCondition.evaluate("$room.available == false", variables));
    }

    @Test
    void evaluatesStringLiteralAndContains() {
        Map<String, Object> variables = Map.of("city", "上海");
        assertTrue(WorkflowCondition.evaluate("$city == '上海'", variables));
        assertTrue(WorkflowCondition.evaluate("$city contains '海'", variables));
        assertFalse(WorkflowCondition.evaluate("$city == '北京'", variables));
        assertFalse(WorkflowCondition.evaluate("$missing == 'x'", variables));
    }

    @Test
    void defaultAndBooleanLiterals() {
        assertFalse(WorkflowCondition.evaluate("default", Map.of()));
        assertTrue(WorkflowCondition.evaluate("true", Map.of()));
        assertFalse(WorkflowCondition.evaluate("false", Map.of()));
    }
}
