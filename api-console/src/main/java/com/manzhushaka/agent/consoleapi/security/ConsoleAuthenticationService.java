package com.manzhushaka.agent.consoleapi.security;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CAPTCHA;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.INVALID_CREDENTIALS;
import static com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException.Reason.SESSION_INVALID;

@Service
public class ConsoleAuthenticationService {
    private static final char[] CAPTCHA_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CAPTCHA_LENGTH = 4;

    private final String configuredUsername;
    private final String configuredPassword;
    private final Duration captchaTtl;
    private final Duration sessionTtl;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final Map<String, CaptchaChallenge> captchaChallenges = new ConcurrentHashMap<>();
    private final Map<String, ConsoleSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public ConsoleAuthenticationService(
            @Value("${agent.console.username}") String configuredUsername,
            @Value("${agent.console.password}") String configuredPassword,
            @Value("${agent.console.captcha-ttl-seconds:120}") long captchaTtlSeconds,
            @Value("${agent.console.session-ttl-seconds:1800}") long sessionTtlSeconds
    ) {
        this(
                configuredUsername,
                configuredPassword,
                Duration.ofSeconds(captchaTtlSeconds),
                Duration.ofSeconds(sessionTtlSeconds),
                Clock.systemUTC(),
                new SecureRandom()
        );
    }

    ConsoleAuthenticationService(
            String configuredUsername,
            String configuredPassword,
            Duration captchaTtl,
            Duration sessionTtl,
            Clock clock,
            SecureRandom secureRandom
    ) {
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
        this.captchaTtl = captchaTtl;
        this.sessionTtl = sessionTtl;
        this.clock = clock;
        this.secureRandom = secureRandom;
    }

    public ConsoleCaptchaResponse createCaptcha() {
        removeExpiredEntries();
        String captchaId = randomToken(18);
        String code = randomCaptchaCode();
        Instant expiresAt = clock.instant().plus(captchaTtl);
        captchaChallenges.put(captchaId, new CaptchaChallenge(code, expiresAt));
        return new ConsoleCaptchaResponse(captchaId, renderCaptcha(code), captchaTtl.toSeconds());
    }

    public ConsoleLoginResponse login(
            String username,
            String password,
            String captchaId,
            String captchaCode
    ) {
        removeExpiredEntries();
        CaptchaChallenge challenge = captchaChallenges.remove(captchaId);
        if (challenge == null
                || !challenge.expiresAt().isAfter(clock.instant())
                || !constantTimeEquals(challenge.code(), normalizeCaptcha(captchaCode))) {
            throw new ConsoleAuthenticationException(INVALID_CAPTCHA);
        }
        if (!constantTimeEquals(configuredUsername, username)
                || !constantTimeEquals(configuredPassword, password)) {
            throw new ConsoleAuthenticationException(INVALID_CREDENTIALS);
        }

        String token = randomToken(32);
        Instant expiresAt = clock.instant().plus(sessionTtl);
        sessions.put(token, new ConsoleSession(configuredUsername, expiresAt));
        return new ConsoleLoginResponse(token, configuredUsername, "ADMIN", expiresAt);
    }

    public String requireSession(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        ConsoleSession session = token == null ? null : sessions.get(token);
        if (session == null || !session.expiresAt().isAfter(clock.instant())) {
            if (token != null) {
                sessions.remove(token);
            }
            throw new ConsoleAuthenticationException(SESSION_INVALID);
        }
        return session.username();
    }

    public void logout(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token != null) {
            sessions.remove(token);
        }
    }

    public boolean usesDemoCredentials() {
        return "admin".equals(configuredUsername) && "Admin123!".equals(configuredPassword);
    }

    private void removeExpiredEntries() {
        Instant now = clock.instant();
        captchaChallenges.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String randomCaptchaCode() {
        StringBuilder code = new StringBuilder(CAPTCHA_LENGTH);
        for (int index = 0; index < CAPTCHA_LENGTH; index++) {
            code.append(CAPTCHA_ALPHABET[secureRandom.nextInt(CAPTCHA_ALPHABET.length)]);
        }
        return code.toString();
    }

    private String randomToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String renderCaptcha(String code) {
        StringBuilder characters = new StringBuilder();
        for (int index = 0; index < code.length(); index++) {
            int x = 23 + index * 27;
            int y = 30 + secureRandom.nextInt(5) - 2;
            int rotation = secureRandom.nextInt(21) - 10;
            characters.append("<text x=\"").append(x).append("\" y=\"").append(y)
                    .append("\" transform=\"rotate(").append(rotation).append(' ').append(x).append(' ').append(y)
                    .append(")\">").append(code.charAt(index)).append("</text>");
        }
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"132\" height=\"44\" viewBox=\"0 0 132 44\">"
                + "<rect width=\"132\" height=\"44\" rx=\"6\" fill=\"#f5f5f7\"/>"
                + "<path d=\"M4 31 C32 4 82 45 128 13 M8 10 C47 36 83 3 126 29\" fill=\"none\" stroke=\"#d7d3cc\" stroke-width=\"1.2\"/>"
                + "<g fill=\"#16130f\" font-family=\"monospace\" font-size=\"24\" font-weight=\"700\">"
                + characters
                + "</g></svg>";
        return "data:image/svg+xml;base64," + Base64.getEncoder()
                .encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeCaptcha(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual == null ? new byte[0] : actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }

    private record CaptchaChallenge(String code, Instant expiresAt) {
    }

    private record ConsoleSession(String username, Instant expiresAt) {
    }
}
