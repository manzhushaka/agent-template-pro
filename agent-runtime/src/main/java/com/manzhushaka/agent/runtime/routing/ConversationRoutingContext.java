package com.manzhushaka.agent.runtime.routing;

public record ConversationRoutingContext(
        String conversationId,
        String currentAgentCode,
        long routingVersion,
        boolean writeOperationPending,
        String visitorId,
        String requestId
) {
}
