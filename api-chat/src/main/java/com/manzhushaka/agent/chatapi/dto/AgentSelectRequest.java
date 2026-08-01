package com.manzhushaka.agent.chatapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AgentSelectRequest(
        @NotBlank String targetAgentCode,
        @Min(0) long expectedRoutingVersion
) {
}
