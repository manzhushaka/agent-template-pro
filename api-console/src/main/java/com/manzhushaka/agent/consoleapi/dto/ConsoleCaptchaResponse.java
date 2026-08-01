package com.manzhushaka.agent.consoleapi.dto;

public record ConsoleCaptchaResponse(
        String captchaId,
        String imageData,
        long expiresInSeconds
) {
}
