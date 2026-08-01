package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.agent.RegisteredDomainAgent;

import java.util.List;

public record PublicAgentResponse(
        String code,
        String displayName,
        String description,
        String iconKey,
        List<SuggestedPromptResponse> suggestedPrompts
) {
    public static PublicAgentResponse from(RegisteredDomainAgent agent) {
        return new PublicAgentResponse(
                agent.descriptor().code(),
                agent.descriptor().displayName(),
                agent.descriptor().description(),
                agent.descriptor().iconKey(),
                agent.descriptor().suggestedPrompts().stream().map(SuggestedPromptResponse::from).toList()
        );
    }
}
