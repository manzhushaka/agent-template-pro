package com.manzhushaka.agent.controlplane;

/** Signals that a write Tool may only be invoked through the Runtime confirmation/action allowlist. */
public final class McpWriteToolConfirmationRequiredException extends IllegalStateException {
    public McpWriteToolConfirmationRequiredException() {
        super("写类型 MCP Tool 必须经 Runtime 动作白名单、任务和二次确认执行。");
    }
}
