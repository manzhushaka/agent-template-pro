package com.manzhushaka.agent.controlplane;

/** Runtime-safe snapshot of a bound MCP tool; the server connection is pre-validated. */
public record McpRuntimeToolSnapshot(
        String toolName,
        boolean writeTool,
        McpServerConnection serverConnection,
        String toolId,
        String toolVersionId
) {
}
