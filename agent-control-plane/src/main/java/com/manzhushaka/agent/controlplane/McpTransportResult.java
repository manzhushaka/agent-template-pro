package com.manzhushaka.agent.controlplane;

/** Stable, non-sensitive outcome of an MCP connection attempt. */
public record McpTransportResult(String status, String message) {
}
