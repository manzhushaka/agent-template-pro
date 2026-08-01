package com.manzhushaka.agent.runtime.agent;

import com.manzhushaka.agent.spi.action.AgentAction;
import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;

import java.util.Map;

public record RegisteredDomainAgent(
        DomainAgentDescriptor descriptor,
        Map<String, AgentAction> actions
) {
    public RegisteredDomainAgent {
        actions = Map.copyOf(actions);
    }
}
