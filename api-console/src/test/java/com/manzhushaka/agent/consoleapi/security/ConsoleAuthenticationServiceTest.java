package com.manzhushaka.agent.consoleapi.security;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CAPTCHA;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CREDENTIALS;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.SESSION_INVALID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsoleAuthenticationServiceTest {
    private static final Pattern CAPTCHA_CHARACTER = Pattern.compile(">([A-Z2-9])</text>");
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void createsOneTimeCaptchaAndAuthenticatedSession() {
        ConsoleAuthenticationService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        ConsoleCaptchaResponse captcha = service.createCaptcha();

        ConsoleLoginResponse session = service.login(
                "admin",
                "Admin123!",
                captcha.captchaId(),
                captchaCode(captcha)
        );

        assertEquals("admin", service.requireSession("Bearer " + session.token()));
        assertEquals(NOW.plusSeconds(1800), session.expiresAt());
        ConsoleAuthenticationException reusedCaptcha = assertThrows(
                ConsoleAuthenticationException.class,
                () -> service.login("admin", "Admin123!", captcha.captchaId(), captchaCode(captcha))
        );
        assertEquals(INVALID_CAPTCHA, reusedCaptcha.reason());
    }

    @Test
    void rejectsCredentialsAndConsumesCaptcha() {
        ConsoleAuthenticationService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        ConsoleCaptchaResponse captcha = service.createCaptcha();

        ConsoleAuthenticationException rejected = assertThrows(
                ConsoleAuthenticationException.class,
                () -> service.login("admin", "wrong", captcha.captchaId(), captchaCode(captcha))
        );

        assertEquals(INVALID_CREDENTIALS, rejected.reason());
        ConsoleAuthenticationException reusedCaptcha = assertThrows(
                ConsoleAuthenticationException.class,
                () -> service.login("admin", "Admin123!", captcha.captchaId(), captchaCode(captcha))
        );
        assertEquals(INVALID_CAPTCHA, reusedCaptcha.reason());
    }

    @Test
    void logoutInvalidatesSession() {
        ConsoleAuthenticationService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        ConsoleCaptchaResponse captcha = service.createCaptcha();
        ConsoleLoginResponse session = service.login("admin", "Admin123!", captcha.captchaId(), captchaCode(captcha));

        service.logout("Bearer " + session.token());

        ConsoleAuthenticationException rejected = assertThrows(
                ConsoleAuthenticationException.class,
                () -> service.requireSession("Bearer " + session.token())
        );
        assertEquals(SESSION_INVALID, rejected.reason());
    }

    private ConsoleAuthenticationService service(Clock clock) {
        return new ConsoleAuthenticationService(
                "admin",
                "Admin123!",
                Duration.ofSeconds(120),
                Duration.ofSeconds(1800),
                clock,
                new SecureRandom()
        );
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
}
