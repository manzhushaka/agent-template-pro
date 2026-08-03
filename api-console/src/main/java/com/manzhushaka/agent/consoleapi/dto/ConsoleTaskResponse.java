package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;

public record ConsoleTaskResponse(
        String id,
        String visitorRef,
        String conversationId,
        String domainCode,
        String agentName,
        String actionCode,
        String status,
        long version,
        int confirmationVersion,
        Instant confirmationExpiresAt,
        String externalRef,
        String resultSummary,
        String lastErrorCode,
        Instant nextRecoveryAt,
        int recoveryAttempts,
        Instant createdAt,
        Instant updatedAt
) {
}
