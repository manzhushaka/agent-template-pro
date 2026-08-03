package com.manzhushaka.agent.runtime.trace;

import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/** Default {@link TraceQueryPort} backed by the durable local trace store. */
@Service
public class LocalTraceQueryService implements TraceQueryPort {
    private final TraceStore traceStore;

    public LocalTraceQueryService(ObjectProvider<TraceStore> traceStore) {
        this.traceStore = traceStore.getIfAvailable(() -> null);
    }

    @Override
    public SpanPage query(SpanQuery query) {
        if (traceStore == null) {
            return new SpanPage(List.of(), 0);
        }
        return traceStore.querySpans(query);
    }

    @Override
    public List<SpanRecord> trace(String traceId) {
        if (traceStore == null) {
            return List.of();
        }
        return traceStore.spansByTrace(traceId);
    }

    @Override
    public List<SpanRecord> recent(SpanType type, int limit) {
        if (traceStore == null) {
            return List.of();
        }
        return traceStore.recentSpans(type, limit);
    }
}
