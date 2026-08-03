package com.manzhushaka.agent.consoleapi.config;

import com.manzhushaka.agent.controlplane.ProviderConnectionTestResult;
import com.manzhushaka.agent.controlplane.ProviderConnectionTester;

import java.net.URI;
import java.time.Duration;

/**
 * Deliberately disabled until a connector can bind the approved address to the transport.
 * Resolving a hostname before HttpClient connects permits DNS rebinding, so a preflight
 * private-address check is not a valid SSRF control.
 */
final class HttpsProviderConnectionTester implements ProviderConnectionTester {
    @Override
    public ProviderConnectionTestResult test(URI endpoint, String credential, Duration timeout) {
        return new ProviderConnectionTestResult(
                "PROBE_UNAVAILABLE",
                "当前未配置可绑定受控连接地址的 Provider 探测 Adapter，未发起网络连接。"
        );
    }
}
