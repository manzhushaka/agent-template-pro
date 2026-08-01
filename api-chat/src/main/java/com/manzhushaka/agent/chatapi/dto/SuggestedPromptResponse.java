package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.spi.domain.SuggestedPromptDescriptor;

public record SuggestedPromptResponse(String title, String prompt) {
    public static SuggestedPromptResponse from(SuggestedPromptDescriptor value) {
        return new SuggestedPromptResponse(value.title(), value.prompt());
    }
}
