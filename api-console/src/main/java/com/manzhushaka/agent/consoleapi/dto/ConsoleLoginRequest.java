package com.manzhushaka.agent.consoleapi.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsoleLoginRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String captchaId,
        @NotBlank String captchaCode
) {
}
