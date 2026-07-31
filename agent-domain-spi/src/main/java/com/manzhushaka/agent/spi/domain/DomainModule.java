package com.manzhushaka.agent.spi.domain;

import com.manzhushaka.agent.spi.action.AgentAction;
import java.util.Collection;
public interface DomainModule {
    String code();
    String displayName();
    Collection<AgentAction> actions();
}
