package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** INPUT node: parks the run and emits a form.request until the owner submits values. */
@Component
public class WorkflowInputHandler implements WorkflowNodeHandler {
    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.INPUT;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        List<Map<String, Object>> fields = new ArrayList<>();
        Object fieldsValue = context.node().config().get("fields");
        if (fieldsValue instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> field = new LinkedHashMap<>();
                    field.put("name", String.valueOf(map.get("name")));
                    field.put("label", map.get("label") == null ? String.valueOf(map.get("name")) : String.valueOf(map.get("label")));
                    field.put("required", Boolean.TRUE.equals(map.get("required")));
                    fields.add(field);
                }
            }
        }
        return WorkflowNodeResult.waitingInput(context.variables(), fields, List.of());
    }
}
