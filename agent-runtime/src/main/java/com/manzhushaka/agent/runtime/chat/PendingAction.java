package com.manzhushaka.agent.runtime.chat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PendingAction {
    private final String id;
    private final String conversationId;
    private final String domainCode;
    private final String actionCode;
    private final Map<String, Object> input = new LinkedHashMap<>();
    private final Instant expiresAt;
    public PendingAction(
            String id,
            String conversationId,
            String domainCode,
            String actionCode,
            Map<String, Object> input,
            Instant expiresAt
    ) {
        this.id = id;
        this.conversationId = conversationId;
        this.domainCode = domainCode;
        this.actionCode = actionCode;
        this.input.putAll(input);
        this.expiresAt = expiresAt;
    }

    public PendingAction(
            String id,
            String conversationId,
            String actionCode,
            Map<String, Object> input,
            Instant expiresAt
    ) {
        this(id, conversationId, prefix(actionCode), actionCode, input, expiresAt);
    }

    public String id() { return id; }
    public String conversationId() { return conversationId; }
    public String domainCode() { return domainCode; }
    public String actionCode() { return actionCode; }
    public Map<String,Object> input() { return Map.copyOf(input); }
    public Instant expiresAt() { return expiresAt; }
    public void merge(Map<String,Object> values) { values.forEach((key, value) -> { if (value != null && !String.valueOf(value).isBlank()) input.put(key, value); }); }
    public List<String> missing(List<String> fields) { return fields.stream().filter(field -> !input.containsKey(field) || String.valueOf(input.get(field)).isBlank()).toList(); }

    private static String prefix(String actionCode) {
        int separator = actionCode.indexOf('.');
        return separator > 0 ? actionCode.substring(0, separator) : "runtime";
    }
}
