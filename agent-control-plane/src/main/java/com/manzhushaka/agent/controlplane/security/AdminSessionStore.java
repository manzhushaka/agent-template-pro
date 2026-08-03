package com.manzhushaka.agent.controlplane.security;

import java.time.Instant;
import java.util.Optional;

public interface AdminSessionStore {
    void create(String tokenHash, AdminSession session, Instant createdAt);

    Optional<AdminSession> findActive(String tokenHash, Instant now);

    void invalidate(String tokenHash, Instant now);

    default Instant recordLoginFailure(String attemptHash, Instant now, int threshold, java.time.Duration lockDuration) {
        return Instant.EPOCH;
    }

    default java.util.Optional<Instant> loginLockedUntil(String attemptHash, Instant now) {
        return java.util.Optional.empty();
    }

    default void clearLoginFailures(String attemptHash) {
    }
}
