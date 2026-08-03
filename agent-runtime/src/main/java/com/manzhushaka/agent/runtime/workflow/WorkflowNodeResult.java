package com.manzhushaka.agent.runtime.workflow;

import com.manzhushaka.agent.runtime.event.StreamEvent;

import java.util.List;
import java.util.Map;

/** Outcome of one node attempt. WAITING_* results park the run until the external decision arrives. */
public record WorkflowNodeResult(
        WorkflowNodeRunStatus status,
        Map<String, Object> output,
        Map<String, Object> variables,
        List<StreamEvent> events,
        String confirmationTaskId,
        int confirmationVersion,
        String confirmationSnapshotHash,
        List<Map<String, Object>> inputFields,
        String errorCode
) {
    public WorkflowNodeResult {
        output = Map.copyOf(output == null ? Map.of() : output);
        variables = Map.copyOf(variables == null ? Map.of() : variables);
        events = List.copyOf(events == null ? List.of() : events);
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
    }

    public static WorkflowNodeResult succeeded(Map<String, Object> output, Map<String, Object> variables, List<StreamEvent> events) {
        return new WorkflowNodeResult(WorkflowNodeRunStatus.SUCCEEDED, output, variables, events, null, 0, null, List.of(), null);
    }

    public static WorkflowNodeResult failed(Map<String, Object> variables, String errorCode) {
        return new WorkflowNodeResult(WorkflowNodeRunStatus.FAILED, Map.of(), variables, List.of(), null, 0, null, List.of(), errorCode);
    }

    public static WorkflowNodeResult waitingInput(
            Map<String, Object> variables,
            List<Map<String, Object>> fields,
            List<StreamEvent> events
    ) {
        return new WorkflowNodeResult(WorkflowNodeRunStatus.WAITING_INPUT, Map.of(), variables, events, null, 0, null, fields, null);
    }

    public static WorkflowNodeResult waitingConfirmation(
            Map<String, Object> variables,
            String taskId,
            int confirmationVersion,
            String snapshotHash,
            List<StreamEvent> events
    ) {
        return new WorkflowNodeResult(WorkflowNodeRunStatus.WAITING_CONFIRMATION, Map.of(), variables,
                events, taskId, confirmationVersion, snapshotHash, List.of(), null);
    }
}
