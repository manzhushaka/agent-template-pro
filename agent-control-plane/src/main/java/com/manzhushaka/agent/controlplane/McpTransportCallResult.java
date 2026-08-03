package com.manzhushaka.agent.controlplane;

import java.util.Map;

/** Result of a gated MCP tool call. Output is limited and whitelisted; never raw remote bodies. */
public record McpTransportCallResult(
        String status,
        Map<String, Object> output,
        String errorCode
) {
    public static final String OK = "OK";
    public static final String FAILED = "FAILED";
    public static final String RESULT_UNKNOWN = "RESULT_UNKNOWN";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public McpTransportCallResult {
        output = Map.copyOf(output == null ? Map.of() : output);
    }
}
