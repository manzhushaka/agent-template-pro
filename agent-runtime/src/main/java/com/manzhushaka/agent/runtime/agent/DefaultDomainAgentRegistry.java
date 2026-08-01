package com.manzhushaka.agent.runtime.agent;

import com.manzhushaka.agent.spi.action.ActionMode;
import com.manzhushaka.agent.spi.action.AgentAction;
import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;
import com.manzhushaka.agent.spi.domain.DomainModule;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class DefaultDomainAgentRegistry implements DomainAgentRegistry {
    private static final Set<String> ICON_KEYS = Set.of(
            "bot", "hotel", "ticket", "landmark", "shopping-bag", "activity", "map"
    );

    private final List<RegisteredDomainAgent> agents;
    private final Map<String, RegisteredDomainAgent> agentsByCode;
    private final Map<String, OwnedAction> actionsByCode;

    public DefaultDomainAgentRegistry(List<DomainModule> modules) {
        Map<String, RegisteredDomainAgent> registeredAgents = new LinkedHashMap<>();
        Map<String, OwnedAction> registeredActions = new LinkedHashMap<>();
        for (DomainModule module : modules) {
            DomainAgentDescriptor descriptor = module.agentDescriptor();
            validateDescriptor(module, descriptor);
            Map<String, AgentAction> actions = new LinkedHashMap<>();
            for (AgentAction action : module.actions()) {
                validateAction(descriptor.code(), action);
                if (actions.putIfAbsent(action.descriptor().code(), action) != null
                        || registeredActions.putIfAbsent(
                        action.descriptor().code(), new OwnedAction(descriptor.code(), action)) != null) {
                    throw new IllegalStateException("Duplicate action code: " + action.descriptor().code());
                }
            }
            RegisteredDomainAgent registered = new RegisteredDomainAgent(descriptor, actions);
            if (registeredAgents.putIfAbsent(descriptor.code(), registered) != null) {
                throw new IllegalStateException("Duplicate domain agent code: " + descriptor.code());
            }
        }
        this.agentsByCode = Map.copyOf(registeredAgents);
        this.actionsByCode = Map.copyOf(registeredActions);
        this.agents = registeredAgents.values().stream()
                .sorted(Comparator.comparingInt(value -> value.descriptor().displayOrder()))
                .toList();
    }

    @Override
    public List<RegisteredDomainAgent> enabledAgents() {
        return agents;
    }

    @Override
    public Optional<RegisteredDomainAgent> findAgent(String agentCode) {
        return Optional.ofNullable(agentsByCode.get(agentCode));
    }

    @Override
    public Optional<OwnedAction> findAction(String actionCode) {
        return Optional.ofNullable(actionsByCode.get(actionCode));
    }

    private void validateDescriptor(DomainModule module, DomainAgentDescriptor descriptor) {
        if (descriptor == null || blank(descriptor.code()) || !descriptor.code().equals(module.code())) {
            throw new IllegalStateException("Domain agent descriptor must match module code: " + module.code());
        }
        if (descriptor.visibleToVisitor()
                && (blank(descriptor.displayName()) || !ICON_KEYS.contains(descriptor.iconKey()))) {
            throw new IllegalStateException("Visible domain agent has invalid public metadata: " + descriptor.code());
        }
    }

    private void validateAction(String agentCode, AgentAction action) {
        String actionCode = action.descriptor().code();
        if (!actionCode.startsWith(agentCode + ".")) {
            throw new IllegalStateException("Action code does not belong to domain agent: " + actionCode);
        }
        ActionMode mode = action.descriptor().mode();
        if (mode != ActionMode.QUERY && mode != ActionMode.DRAFT
                && blank(action.descriptor().confirmationTitle())) {
            throw new IllegalStateException("High-risk action requires a confirmation title: " + actionCode);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
