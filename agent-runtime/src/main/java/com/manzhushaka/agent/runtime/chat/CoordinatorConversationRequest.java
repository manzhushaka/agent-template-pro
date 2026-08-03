package com.manzhushaka.agent.runtime.chat;

import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;

import java.util.List;

/** Context supplied to the coordinator's natural-language response generator. */
public record CoordinatorConversationRequest(
        String content,
        List<ChatMessage> history,
        List<DomainAgentDescriptor> availableAgents,
        List<String> clarificationCandidates,
        AgentAppRuntimeContext appContext,
        String visitorId,
        String conversationId,
        String requestId,
        String traceId
) {
    public CoordinatorConversationRequest {
        history = history == null ? List.of() : List.copyOf(history);
        availableAgents = availableAgents == null ? List.of() : List.copyOf(availableAgents);
        clarificationCandidates = clarificationCandidates == null
                ? List.of()
                : List.copyOf(clarificationCandidates);
    }

    public CoordinatorConversationRequest(
            String content,
            List<ChatMessage> history,
            List<DomainAgentDescriptor> availableAgents,
            List<String> clarificationCandidates
    ) {
        this(content, history, availableAgents, clarificationCandidates, null, null, null, null, null);
    }
}
