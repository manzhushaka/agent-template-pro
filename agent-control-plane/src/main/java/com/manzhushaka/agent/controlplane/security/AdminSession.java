package com.manzhushaka.agent.controlplane.security;

import java.time.Instant;

/** A server-side console session. The bearer token itself is never persisted. */
public record AdminSession(String username, String role, Instant expiresAt) {
}
