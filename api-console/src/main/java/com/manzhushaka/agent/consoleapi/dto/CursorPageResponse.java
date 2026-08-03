package com.manzhushaka.agent.consoleapi.dto;

import java.util.List;

public record CursorPageResponse<T>(
        List<T> items,
        long nextSequence,
        boolean hasMore
) {
    public CursorPageResponse {
        items = List.copyOf(items);
    }
}
