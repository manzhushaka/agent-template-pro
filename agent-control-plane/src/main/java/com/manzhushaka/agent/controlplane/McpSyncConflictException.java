package com.manzhushaka.agent.controlplane;

public final class McpSyncConflictException extends IllegalStateException {
    public McpSyncConflictException() {
        super("该 MCP Server 已有正在执行的同步任务。");
    }
}
