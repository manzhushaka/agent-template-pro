package com.manzhushaka.agent.controlplane;

import java.util.List;
import java.util.Map;

public final class McpBindingConflictException extends RuntimeException {
    private final List<Map<String, Object>> references;

    public McpBindingConflictException(String message, List<Map<String, Object>> references) {
        super(message);
        this.references = references.stream().map(Map::copyOf).toList();
    }

    public List<Map<String, Object>> references() {
        return references;
    }
}
