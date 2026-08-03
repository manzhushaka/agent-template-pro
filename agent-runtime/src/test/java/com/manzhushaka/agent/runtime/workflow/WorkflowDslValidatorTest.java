package com.manzhushaka.agent.runtime.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDslValidatorTest {
    private static WorkflowDsl dsl(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return new WorkflowDsl("1.0", "wf-test", "测试流程", nodes, edges);
    }

    @Test
    void acceptsValidLinearGraph() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("n1", WorkflowNodeType.VARIABLE_ASSIGN, Map.of("assignments", Map.of("city", "上海"))),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "n1"), edge("n1", "end")));
        WorkflowCompiledGraph graph = WorkflowDslValidator.validate(dsl);
        assertNotNull(graph);
        assertEquals("start", graph.startNodeId());
    }

    @Test
    void rejectsMultipleStarts() {
        WorkflowDsl dsl = dsl(List.of(
                node("start1", WorkflowNodeType.START, Map.of()),
                node("start2", WorkflowNodeType.START, Map.of()),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start1", "end"), edge("start2", "end")));
        WorkflowValidationException exception = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDslValidator.validate(dsl));
        assertTrue(exception.getMessage().contains("START"));
    }

    @Test
    void rejectsUnreachableNode() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("end", WorkflowNodeType.END, Map.of()),
                node("orphan", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "end")));
        WorkflowValidationException exception = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDslValidator.validate(dsl));
        assertTrue(exception.getMessage().contains("不可达"));
    }

    @Test
    void rejectsCycle() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("a", WorkflowNodeType.VARIABLE_ASSIGN, Map.of("assignments", Map.of("x", 1))),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "a"), edge("a", "a"), edge("a", "end")));
        WorkflowValidationException exception = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDslValidator.validate(dsl));
        assertTrue(exception.getMessage().contains("环"));
    }

    @Test
    void rejectsClassifierWithoutDefaultEdge() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("c", WorkflowNodeType.CLASSIFIER, Map.of()),
                node("yes", WorkflowNodeType.END, Map.of()),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "c"), edge("c", "yes", "$x == 1"), edge("c", "end")));
        WorkflowValidationException exception = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDslValidator.validate(dsl));
        assertTrue(exception.getMessage().contains("default"));
    }

    @Test
    void acceptsClassifierWithDefaultAndCondition() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("c", WorkflowNodeType.CLASSIFIER, Map.of()),
                node("yes", WorkflowNodeType.END, Map.of()),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "c"), edge("c", "yes", "$x == 1"), edge("c", "end", "default")));
        WorkflowCompiledGraph graph = WorkflowDslValidator.validate(dsl);
        assertEquals(2, graph.edgesFrom("c").size());
    }

    @Test
    void rejectsBadConditionSyntax() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("c", WorkflowNodeType.CLASSIFIER, Map.of()),
                node("yes", WorkflowNodeType.END, Map.of()),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "c"), edge("c", "yes", "x =="), edge("c", "end", "default")));
        assertThrows(WorkflowValidationException.class, () -> WorkflowDslValidator.validate(dsl));
    }

    @Test
    void rejectsMissingRequiredConfig() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("llm", WorkflowNodeType.LLM, Map.of("outputVar", "out")),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "llm"), edge("llm", "end")));
        WorkflowValidationException exception = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDslValidator.validate(dsl));
        assertTrue(exception.getMessage().contains("prompt"));
    }

    @Test
    void rejectsParallelNodeWithSingleEdge() {
        WorkflowDsl dsl = dsl(List.of(
                node("start", WorkflowNodeType.START, Map.of()),
                node("p", WorkflowNodeType.PARALLEL, Map.of()),
                node("end", WorkflowNodeType.END, Map.of())
        ), List.of(edge("start", "p"), edge("p", "end")));
        assertThrows(WorkflowValidationException.class, () -> WorkflowDslValidator.validate(dsl));
    }

    private static WorkflowNode node(String id, WorkflowNodeType type, Map<String, Object> config) {
        return new WorkflowNode(id, type, id, config);
    }

    private static WorkflowEdge edge(String from, String to) {
        return new WorkflowEdge(from, to, null);
    }

    private static WorkflowEdge edge(String from, String to, String condition) {
        return new WorkflowEdge(from, to, condition);
    }
}
