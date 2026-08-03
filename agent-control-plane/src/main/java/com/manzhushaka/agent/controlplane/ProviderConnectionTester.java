package com.manzhushaka.agent.controlplane;

import java.net.URI;
import java.time.Duration;

/** Performs a bounded real Provider probe and never returns response bodies or credentials. */
@FunctionalInterface
public interface ProviderConnectionTester {
    ProviderConnectionTestResult test(URI endpoint, String credential, Duration timeout);

    static ProviderConnectionTester unavailable() {
        return (endpoint, credential, timeout) -> new ProviderConnectionTestResult(
                "PROBE_UNAVAILABLE",
                "当前部署未配置受控 Provider 探测 Adapter。"
        );
    }
}
