package com.manzhushaka.agent.controlplane.evaluation;

import java.util.List;

/**
 * Redacted outcome of running one case through the shared Runtime chain. No prompt or
 * message content is stored; the text is masked before export.
 */
public record EvaluationRunOutcome(
        String text,
        List<String> eventTypes,
        String routeAgentCode,
        String actionCode,
        String routeSource,
        String routeType,
        List<String> formFields,
        boolean clarificationRequested,
        boolean refused,
        boolean taskCreated,
        boolean confirmationRequired,
        Integer confirmationVersion,
        List<String> toolCodes,
        List<String> knowledgeMatchIds,
        List<String> parameterKeys,
        int tokensUsed,
        long costMicros,
        String errorCode
) {
    public EvaluationRunOutcome {
        eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
        formFields = formFields == null ? List.of() : List.copyOf(formFields);
        toolCodes = toolCodes == null ? List.of() : List.copyOf(toolCodes);
        knowledgeMatchIds = knowledgeMatchIds == null ? List.of() : List.copyOf(knowledgeMatchIds);
        parameterKeys = parameterKeys == null ? List.of() : List.copyOf(parameterKeys);
    }
}
