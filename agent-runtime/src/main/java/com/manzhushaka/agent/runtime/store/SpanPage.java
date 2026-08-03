package com.manzhushaka.agent.runtime.store;

import java.util.List;

public record SpanPage(List<SpanRecord> items, long total) {
    public SpanPage {
        items = List.copyOf(items);
    }
}
