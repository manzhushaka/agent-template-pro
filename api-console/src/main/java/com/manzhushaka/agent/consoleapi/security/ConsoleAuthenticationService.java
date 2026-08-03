package com.manzhushaka.agent.consoleapi.security;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.security.AdminSession;
import com.manzhushaka.agent.controlplane.security.AdminSessionStore;
import com.manzhushaka.agent.controlplane.security.InMemoryAdminSessionStore;
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
    private static final int MAX_LOGIN_FAILURES = 5;
    private static final Duration LOGIN_LOCK_DURATION = Duration.ofMinutes(15);

    private final String configuredUsername;
    private final String configuredPassword;
    private final Duration captchaTtl;
    private final Duration sessionTtl;
    private final Clock clock;
    private final SecureRandom secureRandom;
    private final AdminSessionStore sessionStore;
    private final ControlPlaneService controlPlaneService;
    private final Map<String, CaptchaChallenge> captchaChallenges = new ConcurrentHashMap<>();

    @Autowired
    public ConsoleAuthenticationService(
            @Value("${agent.console.username}") String configuredUsername,
            @Value("${agent.console.password}") String configuredPassword,
            @Value("${agent.console.captcha-ttl-seconds:120}") long captchaTtlSeconds,
            @Value("${agent.console.session-ttl-seconds:1800}") long sessionTtlSeconds,
            AdminSessionStore sessionStore,
            ControlPlaneService controlPlaneService
    ) {
        this(
                configuredUsername,
                configuredPassword,
                Duration.ofSeconds(captchaTtlSeconds),
                Duration.ofSeconds(sessionTtlSeconds),
                Clock.systemUTC(),
                new SecureRandom(),
                sessionStore,
                controlPlaneService
        );
    }

    public ConsoleAuthenticationService(
            String configuredUsername,
            String configuredPassword,
            long captchaTtlSeconds,
            long sessionTtlSeconds
    ) {
        this(
                configuredUsername,
                configuredPassword,
                Duration.ofSeconds(captchaTtlSeconds),
                Duration.ofSeconds(sessionTtlSeconds),
                Clock.systemUTC(),
                new SecureRandom(),
                new InMemoryAdminSessionStore(),
                new ControlPlaneService()
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
        this(
                configuredUsername,
                configuredPassword,
                captchaTtl,
                sessionTtl,
                clock,
                secureRandom,
                new InMemoryAdminSessionStore(),
                new ControlPlaneService()
        );
    }

    ConsoleAuthenticationService(
            String configuredUsername,
            String configuredPassword,
            Duration captchaTtl,
            Duration sessionTtl,
            Clock clock,
            SecureRandom secureRandom,
            AdminSessionStore sessionStore,
            ControlPlaneService controlPlaneService
    ) {
        this.configuredUsername = configuredUsername;
        this.configuredPassword = configuredPassword;
        this.captchaTtl = captchaTtl;
        this.sessionTtl = sessionTtl;
        this.clock = clock;
        this.secureRandom = secureRandom;
        this.sessionStore = sessionStore;
        this.controlPlaneService = controlPlaneService;
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
        return login(username, password, captchaId, captchaCode, "unknown");
    }

    public ConsoleLoginResponse login(
            String username,
            String password,
            String captchaId,
            String captchaCode,
            String clientIdentity
    ) {
        removeExpiredEntries();
        String loginKey = loginAttemptHash(username, clientIdentity);
        if (sessionStore.loginLockedUntil(loginKey, clock.instant()).isPresent()) {
            throw new ConsoleAuthenticationException(ConsoleAuthenticationException.Reason.LOGIN_LOCKED);
        }
        CaptchaChallenge challenge = captchaChallenges.remove(captchaId);
        if (challenge == null
                || !challenge.expiresAt().isAfter(clock.instant())
                || !constantTimeEquals(challenge.code(), normalizeCaptcha(captchaCode))) {
            recordLoginFailure(loginKey);
            throw new ConsoleAuthenticationException(INVALID_CAPTCHA);
        }
        if (!constantTimeEquals(configuredUsername, username)
                || !constantTimeEquals(configuredPassword, password)) {
            recordLoginFailure(loginKey);
            throw new ConsoleAuthenticationException(INVALID_CREDENTIALS);
        }

        String token = randomToken(32);
        Instant expiresAt = clock.instant().plus(sessionTtl);
        String role = "ADMIN";
        sessionStore.clearLoginFailures(loginKey);
        sessionStore.create(tokenHash(token), new AdminSession(configuredUsername, role, expiresAt), clock.instant());
        return new ConsoleLoginResponse(token, configuredUsername, role, expiresAt);
    }

    public String requireSession(String authorizationHeader) {
        return requirePrincipal(authorizationHeader).username();
    }

    public ControlPlanePrincipal requirePrincipal(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        AdminSession session = token == null ? null : sessionStore.findActive(tokenHash(token), clock.instant()).orElse(null);
        if (session == null) {
            if (token != null) {
                sessionStore.invalidate(tokenHash(token), clock.instant());
            }
            throw new ConsoleAuthenticationException(SESSION_INVALID);
        }
        return controlPlaneService.principal(session.username(), session.role());
    }

    public ControlPlanePrincipal requirePermission(String authorizationHeader, String permission) {
        ControlPlanePrincipal principal = requirePrincipal(authorizationHeader);
        controlPlaneService.require(principal, permission);
        return principal;
    }

    public void logout(String authorizationHeader) {
        String token = bearerToken(authorizationHeader);
        if (token != null) {
            sessionStore.invalidate(tokenHash(token), clock.instant());
        }
    }

    public boolean usesDemoCredentials() {
        return "admin".equals(configuredUsername) && "Admin123!".equals(configuredPassword);
    }

    private void removeExpiredEntries() {
        Instant now = clock.instant();
        captchaChallenges.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
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

    private void recordLoginFailure(String loginKey) {
        sessionStore.recordLoginFailure(loginKey, clock.instant(), MAX_LOGIN_FAILURES, LOGIN_LOCK_DURATION);
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

    private String tokenHash(String token) {
        if (token == null) {
            return "";
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        return java.util.HexFormat.of().formatHex(digest);
    }

    private String loginAttemptHash(String username, String clientIdentity) {
        String value = (username == null ? "" : username.trim().toLowerCase(Locale.ROOT))
                + '\u0000' + (clientIdentity == null ? "unknown" : clientIdentity);
        return tokenHash(value);
    }

    private record CaptchaChallenge(String code, Instant expiresAt) {
    }

}
