package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Graph mechanics nodes (START/END/CLASSIFIER/PARALLEL) need no effectful handler. */
@Component
public class WorkflowGraphMechanicsHandler implements WorkflowNodeHandler {
    private static final Set<WorkflowNodeType> TYPES =
            Set.of(WorkflowNodeType.START, WorkflowNodeType.END,
                    WorkflowNodeType.CLASSIFIER, WorkflowNodeType.PARALLEL);

    @Override
    public boolean supports(WorkflowNodeType type) {
        return TYPES.contains(type);
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        return WorkflowNodeResult.succeeded(Map.of(), context.variables(), List.of());
    }
}
