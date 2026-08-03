package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assigns literals or $var references into the run variable map. */
@Component
public class WorkflowVariableAssignHandler implements WorkflowNodeHandler {
    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.VARIABLE_ASSIGN;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        Map<String, Object> variables = new LinkedHashMap<>(context.variables());
        Map<String, Object> output = new LinkedHashMap<>();
        Object assignmentsValue = context.node().config().get("assignments");
        if (assignmentsValue instanceof Map<?, ?> assignments) {
            for (Map.Entry<?, ?> entry : assignments.entrySet()) {
                String name = String.valueOf(entry.getKey());
                Object value = resolve(entry.getValue(), context.variables());
                variables.put(name, value);
                output.put(name, value);
            }
        }
        return WorkflowNodeResult.succeeded(output, variables, List.of());
    }

    private static Object resolve(Object value, Map<String, Object> variables) {
        if (value instanceof String text && text.startsWith("$")) {
            String path = text.substring(1);
            Object current = variables;
            for (String part : path.split("\\.")) {
                if (current instanceof Map<?, ?> map) {
                    current = map.get(part);
                } else {
                    return null;
                }
            }
            return current;
        }
        return value;
    }
}
