package com.manzhushaka.agent.consoleapi.config;

import com.manzhushaka.agent.controlplane.SecretRefResolver;

import java.util.Optional;

/** Local/deployment ENV resolver. K8S and KMS require dedicated adapters and fail closed here. */
final class EnvironmentSecretRefResolver implements SecretRefResolver {
    @Override
    public Optional<String> resolve(String referenceType, String referenceLocator) {
        if (!"ENV".equals(referenceType)) {
            return Optional.empty();
        }
        return Optional.ofNullable(System.getenv(referenceLocator)).filter(value -> !value.isBlank());
    }
}
