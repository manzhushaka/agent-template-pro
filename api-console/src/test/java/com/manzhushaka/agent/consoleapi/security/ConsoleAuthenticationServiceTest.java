package com.manzhushaka.agent.consoleapi.security;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.security.AdminSession;
import com.manzhushaka.agent.controlplane.security.AdminSessionStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;

import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CAPTCHA;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CREDENTIALS;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.SESSION_INVALID;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.LOGIN_LOCKED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void givesSharedSessionStoreOnlyTheTokenHash() {
        RecordingSessionStore store = new RecordingSessionStore();
        ConsoleAuthenticationService service = new ConsoleAuthenticationService(
                "admin",
                "Admin123!",
                Duration.ofSeconds(120),
                Duration.ofSeconds(1800),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SecureRandom(),
                store,
                new ControlPlaneService()
        );
        ConsoleCaptchaResponse captcha = service.createCaptcha();

        ConsoleLoginResponse session = service.login(
                "admin", "Admin123!", captcha.captchaId(), captchaCode(captcha)
        );

        assertFalse(session.token().equals(store.createdTokenHash));
        assertTrue(store.createdTokenHash.matches("[0-9a-f]{64}"));
        assertEquals("admin", service.requireSession("Bearer " + session.token()));
    }

    @Test
    void locksLoginAfterRepeatedFailedCredentials() {
        ConsoleAuthenticationService service = service(Clock.fixed(NOW, ZoneOffset.UTC));
        for (int attempt = 0; attempt < 5; attempt++) {
            ConsoleCaptchaResponse captcha = service.createCaptcha();
            assertEquals(INVALID_CREDENTIALS, assertThrows(ConsoleAuthenticationException.class, () ->
                    service.login("admin", "wrong", captcha.captchaId(), captchaCode(captcha))
            ).reason());
        }

        ConsoleCaptchaResponse captcha = service.createCaptcha();
        assertEquals(LOGIN_LOCKED, assertThrows(ConsoleAuthenticationException.class, () ->
                service.login("admin", "Admin123!", captcha.captchaId(), captchaCode(captcha))
        ).reason());
    }

    @Test
    void exposesStoredNonAdminRoleToRbacGate() {
        RecordingSessionStore store = new RecordingSessionStore();
        store.createdTokenHash = sha256("viewer-token");
        store.session = new AdminSession("viewer", "VIEWER", NOW.plusSeconds(600));
        ConsoleAuthenticationService service = new ConsoleAuthenticationService(
                "admin", "Admin123!", Duration.ofSeconds(120), Duration.ofSeconds(1800),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(), store, new ControlPlaneService()
        );

        var principal = service.requirePrincipal("Bearer viewer-token");
        assertEquals("VIEWER", principal.role());
        assertThrows(com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException.class, () ->
                new ControlPlaneService().require(principal, ControlPlaneService.SECRET_WRITE)
        );
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

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static final class RecordingSessionStore implements AdminSessionStore {
        private String createdTokenHash;
        private AdminSession session;

        @Override
        public void create(String tokenHash, AdminSession session, Instant createdAt) {
            this.createdTokenHash = tokenHash;
            this.session = session;
        }

        @Override
        public Optional<AdminSession> findActive(String tokenHash, Instant now) {
            return tokenHash.equals(createdTokenHash) ? Optional.ofNullable(session) : Optional.empty();
        }

        @Override
        public void invalidate(String tokenHash, Instant now) {
            if (tokenHash.equals(createdTokenHash)) {
                session = null;
            }
        }
    }
}
