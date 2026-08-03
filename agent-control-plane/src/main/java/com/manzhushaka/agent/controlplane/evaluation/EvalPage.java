package com.manzhushaka.agent.controlplane.evaluation;

import java.util.List;

public record EvalPage<T>(List<T> items, long total) {
    public EvalPage {
        items = List.copyOf(items);
    }
}
