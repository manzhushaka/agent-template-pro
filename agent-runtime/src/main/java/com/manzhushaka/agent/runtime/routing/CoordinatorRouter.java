package com.manzhushaka.agent.runtime.routing;

import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;

import java.util.List;

public interface CoordinatorRouter {
    RouteDecision route(
            String content,
            ConversationRoutingContext context,
            List<DomainAgentDescriptor> candidates
    );
}
