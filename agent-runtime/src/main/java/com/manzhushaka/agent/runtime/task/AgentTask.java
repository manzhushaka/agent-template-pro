package com.manzhushaka.agent.runtime.task;

import java.time.Instant;
import java.util.Map;

/**
 * Durable task fact. Mutations are deliberately limited to state transitions so a store can
 * protect them with a conditional update instead of relying on a process-local lock.
 */
public final class AgentTask {
    private final String id;
    private final String visitorId;
    private final String conversationId;
    private final String domainCode;
    private final String actionCode;
    private final String idempotencyKey;
    private final Map<String, Object> input;
    private final Instant createdAt;
    private Instant updatedAt;
    private TaskStatus status;
    private int confirmationVersion;
    private long version;
    private String confirmationSnapshotHash;
    private String externalRef;

    public AgentTask(
            String id,
            String visitorId,
            String conversationId,
            String actionCode,
            String idempotencyKey,
            Map<String, Object> input,
            TaskStatus status,
            int confirmationVersion,
            long version,
            String confirmationSnapshotHash,
            String externalRef,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, visitorId, conversationId, prefix(actionCode), actionCode, idempotencyKey, input, status,
                confirmationVersion, version, confirmationSnapshotHash, externalRef, createdAt, updatedAt);
    }

    public AgentTask(
            String id,
            String visitorId,
            String conversationId,
            String domainCode,
            String actionCode,
            String idempotencyKey,
            Map<String, Object> input
    ) {
        this(id, visitorId, conversationId, domainCode, actionCode, idempotencyKey, input, TaskStatus.CREATED,
                0, 0, null, null, Instant.now(), Instant.now());
    }

    public AgentTask(
            String id,
            String visitorId,
            String conversationId,
            String actionCode,
            String idempotencyKey,
            Map<String, Object> input
    ) {
        this(id, visitorId, conversationId, prefix(actionCode), actionCode, idempotencyKey, input);
    }

    public AgentTask(
            String id,
            String visitorId,
            String conversationId,
            String domainCode,
            String actionCode,
            String idempotencyKey,
            Map<String, Object> input,
            TaskStatus status,
            int confirmationVersion,
            long version,
            String confirmationSnapshotHash,
            String externalRef,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.visitorId = visitorId;
        this.conversationId = conversationId;
        this.domainCode = domainCode;
        this.actionCode = actionCode;
        this.idempotencyKey = idempotencyKey;
        this.input = Map.copyOf(input);
        this.status = status;
        this.confirmationVersion = confirmationVersion;
        this.version = version;
        this.confirmationSnapshotHash = confirmationSnapshotHash;
        this.externalRef = externalRef;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String id() { return id; }
    public String visitorId() { return visitorId; }
    public String conversationId() { return conversationId; }
    public String domainCode() { return domainCode; }
    public String actionCode() { return actionCode; }
    public String idempotencyKey() { return idempotencyKey; }
    public Map<String, Object> input() { return input; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public TaskStatus status() { return status; }
    public int confirmationVersion() { return confirmationVersion; }
    public long version() { return version; }
    public String confirmationSnapshotHash() { return confirmationSnapshotHash; }
    public String externalRef() { return externalRef; }

    public void prepareConfirmation(int newConfirmationVersion, String snapshotHash) {
        this.status = TaskStatus.WAITING_CONFIRMATION;
        this.confirmationVersion = newConfirmationVersion;
        this.confirmationSnapshotHash = snapshotHash;
        touch();
    }

    public void applyTransition(TaskStatus targetStatus, String updatedExternalRef) {
        this.status = targetStatus;
        if (updatedExternalRef != null) {
            this.externalRef = updatedExternalRef;
        }
        this.version++;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static String prefix(String actionCode) {
        int separator = actionCode.indexOf('.');
        return separator > 0 ? actionCode.substring(0, separator) : "runtime";
    }
}
