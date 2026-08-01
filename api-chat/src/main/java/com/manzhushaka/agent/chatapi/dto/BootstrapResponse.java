package com.manzhushaka.agent.chatapi.dto;

import java.util.List;

public record BootstrapResponse(
        ApplicationIdentity application,
        CoordinatorIdentity coordinator,
        List<PublicAgentResponse> agents
) {
    public record ApplicationIdentity(String code, String displayName) {
    }

    public record CoordinatorIdentity(String code, String displayName, String description) {
    }
}
