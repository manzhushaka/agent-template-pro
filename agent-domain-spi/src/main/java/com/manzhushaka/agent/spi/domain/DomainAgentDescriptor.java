package com.manzhushaka.agent.spi.domain;

import java.util.List;

/** Stable, code-owned metadata for a domain agent. */
public record DomainAgentDescriptor(
        String code,
        String displayName,
        String description,
        String iconKey,
        String routingDescription,
        List<String> routingHints,
        List<SuggestedPromptDescriptor> suggestedPrompts,
        int displayOrder,
        boolean visibleToVisitor
) {
    public DomainAgentDescriptor {
        routingHints = routingHints == null ? List.of() : List.copyOf(routingHints);
        suggestedPrompts = suggestedPrompts == null ? List.of() : List.copyOf(suggestedPrompts);
    }

    public static DomainAgentDescriptor basic(String code, String displayName) {
        return new DomainAgentDescriptor(
                code,
                displayName,
                "",
                "bot",
                "",
                List.of(),
                List.of(),
                100,
                false
        );
    }
}
