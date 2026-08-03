package com.manzhushaka.agent.controlplane;

public record ProviderConnectionTestResult(String status, String message) {
    public ProviderConnectionTestResult {
        if (!"CONNECTED".equals(status) && !"CONNECTION_FAILED".equals(status)
                && !"PROBE_UNAVAILABLE".equals(status)) {
            throw new IllegalArgumentException("不支持的 Provider 连接测试状态。");
        }
    }
}
