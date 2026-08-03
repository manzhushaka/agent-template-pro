package com.manzhushaka.agent.controlplane;

public final class McpDuplicateResourceException extends IllegalStateException {
    public McpDuplicateResourceException() {
        super("MCP 资源已存在。");
    }
}
