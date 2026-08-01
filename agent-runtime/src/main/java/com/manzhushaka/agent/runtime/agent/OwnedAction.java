package com.manzhushaka.agent.runtime.agent;

import com.manzhushaka.agent.spi.action.AgentAction;

public record OwnedAction(String agentCode, AgentAction action) {
}
