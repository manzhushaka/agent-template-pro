package com.manzhushaka.agent.runtime.store;

import java.util.List;
import java.util.Optional;

/**
 * Durable trace fact store. Implementations must never persist prompt/message content; only
 * whitelisted span fields and masked metadata are allowed.
 */
public interface TraceStore {
    void saveSpan(SpanRecord span);

    SpanPage querySpans(SpanQuery query);

    List<SpanRecord> spansByTrace(String traceId);

    Optional<SpanRecord> findSpan(String spanId);

    List<SpanRecord> recentSpans(SpanType type, int limit);
}
