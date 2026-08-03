package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleOverviewResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.service.ConsoleRuntimeQueryService;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConsoleControllerTest {
    private static final Pattern CAPTCHA_CHARACTER = Pattern.compile(">([A-Z2-9])</text>");

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
        ConsoleRuntimeQueryService runtimeQueryService = mock(ConsoleRuntimeQueryService.class);
        when(orchestrator.tasks()).thenReturn(List.of());
        when(runtimeQueryService.overview()).thenReturn(new ConsoleOverviewResponse(
                "READY", "in-memory", 0, 0, 0, 0, 0
        ));
        when(runtimeQueryService.tasks(1, 20, null, null, null))
                .thenReturn(new PageResponse<>(List.of(), 1, 20, 0, 0));
        ConsoleAuthenticationService authenticationService = new ConsoleAuthenticationService(
                "admin",
                "Admin123!",
                120,
                1800
        );
        client = WebTestClient.bindToController(
                        new ConsoleController(
                                orchestrator,
                                authenticationService,
                                runtimeQueryService,
                                new MockEnvironment()
                        )
                )
                .build();
    }

    @Test
    void logsInWithCaptchaAndProtectsConsoleEndpoints() {
        ConsoleCaptchaResponse captcha = captcha();
        ConsoleLoginResponse login = client.post()
                .uri("/api/console/v1/auth/login")
                .bodyValue(new LoginRequest(
                        "admin",
                        "Admin123!",
                        captcha.captchaId(),
                        captchaCode(captcha)
                ))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(ConsoleLoginResponse.class)
                .returnResult()
                .getResponseBody();

        client.get()
                .uri("/api/console/v1/overview")
                .header("Authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runtimeStatus").isEqualTo("READY")
                .jsonPath("$.taskTotal").isEqualTo(0);

        client.get()
                .uri("/api/console/v1/tasks")
                .header("Authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items").isArray()
                .jsonPath("$.page").isEqualTo(1)
                .jsonPath("$.total").isEqualTo(0);

        client.post()
                .uri("/api/console/v1/auth/logout")
                .header("Authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().isNoContent();

        client.get()
                .uri("/api/console/v1/overview")
                .header("Authorization", "Bearer " + login.token())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_SESSION_INVALID");
    }

    @Test
    void returnsStableErrorForInvalidCaptcha() {
        ConsoleCaptchaResponse captcha = captcha();

        client.post()
                .uri("/api/console/v1/auth/login")
                .bodyValue(new LoginRequest("admin", "Admin123!", captcha.captchaId(), "WRONG"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_CAPTCHA_INVALID");
    }

    @Test
    void rateLimitsRepeatedFailedLogins() {
        for (int attempt = 0; attempt < 5; attempt++) {
            ConsoleCaptchaResponse captcha = captcha();
            client.post().uri("/api/console/v1/auth/login")
                    .bodyValue(new LoginRequest("admin", "wrong", captcha.captchaId(), captchaCode(captcha)))
                    .exchange().expectStatus().isUnauthorized();
        }
        ConsoleCaptchaResponse captcha = captcha();
        client.post().uri("/api/console/v1/auth/login")
                .bodyValue(new LoginRequest("admin", "Admin123!", captcha.captchaId(), captchaCode(captcha)))
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals("Retry-After", "900")
                .expectBody().jsonPath("$.code").isEqualTo("CONSOLE_LOGIN_LOCKED");
    }

    private ConsoleCaptchaResponse captcha() {
        return client.get()
                .uri("/api/console/v1/auth/captcha")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody(ConsoleCaptchaResponse.class)
                .returnResult()
                .getResponseBody();
    }

    private String captchaCode(ConsoleCaptchaResponse response) {
        String encodedSvg = response.imageData().substring(response.imageData().indexOf(',') + 1);
        String svg = new String(Base64.getDecoder().decode(encodedSvg), StandardCharsets.UTF_8);
        Matcher matcher = CAPTCHA_CHARACTER.matcher(svg);
        StringBuilder code = new StringBuilder();
        while (matcher.find()) {
            code.append(matcher.group(1));
        }
        return code.toString();
    }

    private record LoginRequest(
            String username,
            String password,
            String captchaId,
            String captchaCode
    ) {
    }
}
