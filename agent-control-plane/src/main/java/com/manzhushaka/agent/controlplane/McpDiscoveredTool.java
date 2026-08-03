package com.manzhushaka.agent.controlplane;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, credential-free description obtained from an approved MCP endpoint. */
public record McpDiscoveredTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String riskLevel,
        boolean writeTool
) {
    public McpDiscoveredTool {
        inputSchema = inputSchema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchema));
        outputSchema = outputSchema == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(outputSchema));
    }
}
