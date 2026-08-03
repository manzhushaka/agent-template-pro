package com.manzhushaka.agent.runtime.trace;

import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanType;

import java.util.List;

/**
 * Port for reading traces. The default implementation reads the local durable store; a
 * deployment may swap this for an external trace backend (OTel/ES) without touching the
 * control plane.
 */
public interface TraceQueryPort {
    SpanPage query(SpanQuery query);

    List<SpanRecord> trace(String traceId);

    List<SpanRecord> recent(SpanType type, int limit);
}
