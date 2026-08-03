package com.manzhushaka.agent.controlplane.security;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Default local-demo store. The JDBC implementation replaces it in runtime-jdbc deployments. */
public final class InMemoryAdminSessionStore implements AdminSessionStore {
    private final Map<String, StoredSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();

    @Override
    public void create(String tokenHash, AdminSession session, Instant createdAt) {
        sessions.put(tokenHash, new StoredSession(session, false));
    }

    @Override
    public Optional<AdminSession> findActive(String tokenHash, Instant now) {
        StoredSession stored = sessions.get(tokenHash);
        if (stored == null || stored.revoked() || !stored.session().expiresAt().isAfter(now)) {
            if (stored != null && !stored.session().expiresAt().isAfter(now)) {
                sessions.replace(tokenHash, stored, new StoredSession(stored.session(), true));
            }
            return Optional.empty();
        }
        return Optional.of(stored.session());
    }

    @Override
    public void invalidate(String tokenHash, Instant now) {
        sessions.computeIfPresent(tokenHash, (key, stored) -> new StoredSession(stored.session(), true));
    }

    @Override
    public Instant recordLoginFailure(String attemptHash, Instant now, int threshold, java.time.Duration lockDuration) {
        return loginAttempts.compute(attemptHash, (key, previous) -> {
            int failures = previous == null || previous.lastFailedAt().plus(lockDuration).isBefore(now) ? 1 : previous.failures() + 1;
            Instant lockedUntil = failures >= threshold ? now.plus(lockDuration) : now;
            return new LoginAttempt(failures, lockedUntil, now);
        }).lockedUntil();
    }

    @Override
    public Optional<Instant> loginLockedUntil(String attemptHash, Instant now) {
        LoginAttempt attempt = loginAttempts.get(attemptHash);
        return attempt != null && attempt.lockedUntil().isAfter(now) ? Optional.of(attempt.lockedUntil()) : Optional.empty();
    }

    @Override
    public void clearLoginFailures(String attemptHash) {
        loginAttempts.remove(attemptHash);
    }

    private record StoredSession(AdminSession session, boolean revoked) {
    }

    private record LoginAttempt(int failures, Instant lockedUntil, Instant lastFailedAt) {
    }
}
