package com.manzhushaka.agent.controlplane.security;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** MySQL-backed shared sessions. `token_hash` is the only token representation written to SQL. */
public final class JdbcAdminSessionStore implements AdminSessionStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAdminSessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void create(String tokenHash, AdminSession session, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO agent_admin_session (id, token_hash, admin_username, role_code, expires_at, revoked_at, created_at, updated_at) "
                        + "VALUES (UUID(), ?, ?, ?, ?, NULL, ?, ?)",
                tokenHash, session.username(), session.role(), timestamp(session.expiresAt()), timestamp(createdAt), timestamp(createdAt)
        );
    }

    @Override
    public Optional<AdminSession> findActive(String tokenHash, Instant now) {
        jdbcTemplate.update(
                "UPDATE agent_admin_session SET revoked_at = ?, updated_at = ? "
                        + "WHERE token_hash = ? AND revoked_at IS NULL AND expires_at <= ?",
                timestamp(now), timestamp(now), tokenHash, timestamp(now)
        );
        return jdbcTemplate.query(
                "SELECT admin_username, role_code, expires_at FROM agent_admin_session "
                        + "WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > ?",
                (resultSet, rowNumber) -> new AdminSession(
                        resultSet.getString("admin_username"),
                        resultSet.getString("role_code"),
                        resultSet.getTimestamp("expires_at").toInstant()
                ),
                tokenHash, timestamp(now)
        ).stream().findFirst();
    }

    @Override
    public void invalidate(String tokenHash, Instant now) {
        jdbcTemplate.update(
                "UPDATE agent_admin_session SET revoked_at = ?, updated_at = ? "
                        + "WHERE token_hash = ? AND revoked_at IS NULL",
                timestamp(now), timestamp(now), tokenHash
        );
    }

    @Override
    public Instant recordLoginFailure(String attemptHash, Instant now, int threshold, java.time.Duration lockDuration) {
        jdbcTemplate.update("INSERT INTO agent_admin_login_attempt (attempt_hash, failure_count, locked_until, last_failed_at, created_at, updated_at) VALUES (?, 1, NULL, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE failure_count = IF(last_failed_at < DATE_SUB(VALUES(last_failed_at), INTERVAL 15 MINUTE), 1, failure_count + 1), last_failed_at = VALUES(last_failed_at), updated_at = VALUES(updated_at)",
                attemptHash, timestamp(now), timestamp(now), timestamp(now));
        Integer failures = jdbcTemplate.queryForObject("SELECT failure_count FROM agent_admin_login_attempt WHERE attempt_hash = ?", Integer.class, attemptHash);
        Instant lockedUntil = failures != null && failures >= threshold ? now.plus(lockDuration) : now;
        if (failures != null && failures >= threshold) {
            jdbcTemplate.update("UPDATE agent_admin_login_attempt SET locked_until = ?, updated_at = ? WHERE attempt_hash = ?", timestamp(lockedUntil), timestamp(now), attemptHash);
        }
        return lockedUntil;
    }

    @Override
    public Optional<Instant> loginLockedUntil(String attemptHash, Instant now) {
        return jdbcTemplate.query("SELECT locked_until FROM agent_admin_login_attempt WHERE attempt_hash = ? AND locked_until > ?", (rs, row) -> rs.getTimestamp(1).toInstant(), attemptHash, timestamp(now)).stream().findFirst();
    }

    @Override
    public void clearLoginFailures(String attemptHash) {
        jdbcTemplate.update("DELETE FROM agent_admin_login_attempt WHERE attempt_hash = ?", attemptHash);
    }

    private Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
