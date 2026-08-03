package com.manzhushaka.agent.controlplane;

import java.util.Set;

public record ControlPlanePrincipal(String username, String role, Set<String> permissions) {
    public ControlPlanePrincipal {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
}
