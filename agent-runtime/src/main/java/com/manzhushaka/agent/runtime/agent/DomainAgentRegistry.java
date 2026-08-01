package com.manzhushaka.agent.runtime.agent;

import java.util.List;
import java.util.Optional;

public interface DomainAgentRegistry {
    List<RegisteredDomainAgent> enabledAgents();

    Optional<RegisteredDomainAgent> findAgent(String agentCode);

    Optional<OwnedAction> findAction(String actionCode);
}
