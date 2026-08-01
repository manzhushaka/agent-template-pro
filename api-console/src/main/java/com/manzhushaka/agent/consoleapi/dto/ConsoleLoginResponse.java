package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;

public record ConsoleLoginResponse(
        String token,
        String username,
        String role,
        Instant expiresAt
) {
}
