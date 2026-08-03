package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACTION node: deterministic domain action execution through ChatOrchestrator. QUERY/DRAFT execute
 * directly; write modes create a WAITING_CONFIRMATION task and park until the versioned decision.
 */
@Component
public class WorkflowActionHandler implements WorkflowNodeHandler {
    private static final String CONVERSATION_VAR = "_wfConversationId";

    private final ChatOrchestrator orchestrator;

    public WorkflowActionHandler(ChatOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.ACTION;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        String agentCode = string(context.node().config().get("agentCode"));
        String actionCode = string(context.node().config().get("actionCode"));
        String resultVar = string(context.node().config().get("resultVar"));
        Map<String, Object> input = resolveInput(context);
        Map<String, Object> variables = new LinkedHashMap<>(context.variables());
        String conversationId = string(variables.get(CONVERSATION_VAR));
        if (conversationId.isBlank()) {
            conversationId = orchestrator.createConversation(context.visitorRef()).id();
            variables.put(CONVERSATION_VAR, conversationId);
        }
        WorkflowNodeRun nodeRun = context.runStore().findNodeRun(context.run().id(), context.node().id())
                .orElse(null);
        if (nodeRun != null && nodeRun.confirmationTaskId() != null) {
            AgentTask task = context.confirmationGate().confirmedTask(
                    context.visitorRef(), nodeRun.confirmationTaskId());
            List<StreamEvent> events = orchestrator.executeDispatchedWorkflowTask(task, context.requestId());
            Map<String, Object> output = outputOf(events, resultVar);
            return WorkflowNodeResult.succeeded(output, variables, events);
        }
        List<StreamEvent> events = orchestrator.executeWorkflowAction(
                context.visitorRef(), conversationId, context.requestId(),
                agentCode, actionCode, input
        );
        for (StreamEvent event : events) {
            if ("action.confirm".equals(event.type())) {
                String taskId = string(event.payload().get("taskId"));
                int version = event.payload().get("confirmationVersion") instanceof Number number
                        ? number.intValue() : 1;
                AgentTask task = context.confirmationGate().pendingTask(context.visitorRef(), taskId);
                return WorkflowNodeResult.waitingConfirmation(
                        variables, taskId, version, task.confirmationSnapshotHash(), List.of());
            }
            if ("task.status".equals(event.type())
                    && "WAITING_CONFIRMATION".equals(string(event.payload().get("status")))) {
                String taskId = string(event.payload().get("taskId"));
                int version = event.payload().get("confirmationVersion") instanceof Number number
                        ? number.intValue() : 1;
                AgentTask task = context.confirmationGate().pendingTask(context.visitorRef(), taskId);
                return WorkflowNodeResult.waitingConfirmation(
                        variables, taskId, version, task.confirmationSnapshotHash(), List.of());
            }
            if ("form.request".equals(event.type())) {
                List<Map<String, Object>> fields = new ArrayList<>();
                if (event.payload().get("fields") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Map<String, Object> field = new LinkedHashMap<>();
                            field.put("name", String.valueOf(map.get("name")));
                            field.put("label", map.get("label") == null ? map.get("name") : map.get("label"));
                            field.put("required", map.get("required"));
                            fields.add(field);
                        }
                    }
                }
                return WorkflowNodeResult.waitingInput(variables, fields, List.of());
            }
        }
        Map<String, Object> output = outputOf(events, resultVar);
        return WorkflowNodeResult.succeeded(output, variables, events);
    }

    private Map<String, Object> resolveInput(WorkflowNodeContext context) {
        Object inputValue = context.node().config().get("input");
        if (inputValue instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        String inputVar = string(context.node().config().get("inputVar"));
        if (!inputVar.isBlank() && context.variables().get(inputVar) instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static Map<String, Object> outputOf(List<StreamEvent> events, String resultVar) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (StreamEvent event : events) {
            if ("message.final".equals(event.type()) && event.payload().get("content") != null) {
                output.put("summary", String.valueOf(event.payload().get("content")));
            }
            if ("card.render".equals(event.type())) {
                output.put("cardType", event.payload().get("cardType"));
                output.put("cardData", event.payload().get("data"));
            }
        }
        if (!resultVar.isBlank()) {
            return Map.of(resultVar, Map.copyOf(output));
        }
        return Map.copyOf(output);
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
