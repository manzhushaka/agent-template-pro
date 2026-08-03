package com.manzhushaka.agent.controlplane;

import java.util.List;

/** Resolved only at invocation time. Credentials are intentionally unavailable to API callers. */
public record McpServerConnection(
        String serverId,
        String transport,
        String endpoint,
        String command,
        List<String> arguments,
        String credential
) {
    public McpServerConnection {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    @Override
    public String toString() {
        return "McpServerConnection[serverId=" + serverId + ", transport=" + transport + ", endpoint=" + endpoint
                + ", credentialConfigured=" + (credential != null && !credential.isBlank()) + "]";
    }
}
