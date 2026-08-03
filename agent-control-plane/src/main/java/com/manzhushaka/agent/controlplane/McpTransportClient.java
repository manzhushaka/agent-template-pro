package com.manzhushaka.agent.controlplane;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Controlled MCP transport boundary. Implementations must never expose credentials or raw remote
 * bodies to callers, and may only connect to a server already accepted by {@link McpControlPlaneService}.
 */
public interface McpTransportClient {
    McpTransportResult test(McpServerConnection server, Duration timeout);

    List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout);

    /**
     * Executes a tool call through the controlled transport. Callers must already hold a confirmed
     * gate decision for write tools; this boundary never decides permissions.
     */
    McpTransportCallResult call(
            McpServerConnection server,
            String toolName,
            Map<String, Object> arguments,
            Duration timeout
    );

    static McpTransportClient unavailable() {
        return new McpTransportClient() {
            @Override
            public McpTransportResult test(McpServerConnection server, Duration timeout) {
                return new McpTransportResult("PROBE_UNAVAILABLE", "当前部署未配置 MCP 受控传输 Adapter。");
            }

            @Override
            public List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout) {
                throw new IllegalStateException("当前部署未配置 MCP 能力发现 Adapter。");
            }

            @Override
            public McpTransportCallResult call(
                    McpServerConnection server,
                    String toolName,
                    Map<String, Object> arguments,
                    Duration timeout
            ) {
                return new McpTransportCallResult(
                        McpTransportCallResult.UNAVAILABLE,
                        Map.of(),
                        "MCP_ADAPTER_UNAVAILABLE"
                );
            }
        };
    }
}
