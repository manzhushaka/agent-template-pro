package com.manzhushaka.agent.runtime.intent;

import java.util.Map;

/** Model or deterministic interpretation output. It is not an authorization decision. */
public record IntentDecision(String actionCode, Map<String, Object> input) {
    public IntentDecision {
        input = Map.copyOf(input);
    }
}
