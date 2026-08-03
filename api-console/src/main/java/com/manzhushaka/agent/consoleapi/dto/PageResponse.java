package com.manzhushaka.agent.consoleapi.dto;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages
) {
    public PageResponse {
        items = List.copyOf(items);
    }
}
