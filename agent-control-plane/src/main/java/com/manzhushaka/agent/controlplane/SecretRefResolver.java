package com.manzhushaka.agent.controlplane;

import java.util.Optional;

/** Resolves secret material at use time without persisting or returning it. */
@FunctionalInterface
public interface SecretRefResolver {
    Optional<String> resolve(String referenceType, String referenceLocator);

    static SecretRefResolver unavailable() {
        return (referenceType, referenceLocator) -> Optional.empty();
    }
}
