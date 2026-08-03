package com.manzhushaka.agent.runtime.model;

import com.manzhushaka.agent.spi.action.ActionDescriptor;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves model-selected action codes only against a caller-provided action whitelist. */
public final class ModelActionSelectionPolicy {
    public ActionDescriptor select(String actionCode, Collection<ActionDescriptor> allowedActions) {
        Objects.requireNonNull(actionCode, "actionCode cannot be null");
        Objects.requireNonNull(allowedActions, "allowedActions cannot be null");

        Map<String, ActionDescriptor> allowedByCode = new LinkedHashMap<>();
        for (ActionDescriptor descriptor : allowedActions) {
            ActionDescriptor previous = allowedByCode.putIfAbsent(descriptor.code(), descriptor);
            if (previous != null) {
                throw new IllegalArgumentException("Action whitelist contains duplicate code: " + descriptor.code());
            }
        }

        ActionDescriptor selected = allowedByCode.get(actionCode);
        if (selected == null) {
            throw new IllegalArgumentException("Model selected an action outside the provided whitelist");
        }
        return selected;
    }
}
