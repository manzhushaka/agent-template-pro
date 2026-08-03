package com.manzhushaka.agent.controlplane;

import java.util.List;
import java.util.Map;

public final class McpToolReferenceConflictException extends IllegalStateException {
    private final String toolId;
    private final List<Map<String, Object>> references;

    public McpToolReferenceConflictException(String toolId, List<Map<String, Object>> references) {
        super("MCP Tool 仍被 Agent 绑定引用，不能停用。");
        this.toolId = toolId;
        this.references = List.copyOf(references);
    }

    public String toolId() { return toolId; }
    public List<Map<String, Object>> references() { return references; }
}
