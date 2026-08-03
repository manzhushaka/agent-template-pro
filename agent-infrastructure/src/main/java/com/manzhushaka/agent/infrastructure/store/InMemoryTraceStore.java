package com.manzhushaka.agent.infrastructure.store;

import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local trace store used when the runtime is not backed by MySQL. */
@Repository
@Profile("!runtime-jdbc")
public class InMemoryTraceStore implements TraceStore {
    private final List<SpanRecord> spans = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, SpanRecord> byId = new ConcurrentHashMap<>();

    @Override
    public void saveSpan(SpanRecord span) {
        byId.put(span.id(), span);
        spans.add(span);
    }

    @Override
    public SpanPage querySpans(SpanQuery query) {
        List<SpanRecord> filtered = spans.stream()
                .sorted(Comparator.comparing(SpanRecord::startedAt).reversed())
                .filter(span -> matches(span, query))
                .toList();
        int fromIndex = (query.page() - 1) * query.size();
        if (fromIndex >= filtered.size()) {
            return new SpanPage(List.of(), filtered.size());
        }
        int toIndex = Math.min(filtered.size(), fromIndex + query.size());
        return new SpanPage(filtered.subList(fromIndex, toIndex), filtered.size());
    }

    @Override
    public List<SpanRecord> spansByTrace(String traceId) {
        return spans.stream()
                .filter(span -> span.traceId().equals(traceId))
                .sorted(Comparator.comparing(SpanRecord::startedAt))
                .toList();
    }

    @Override
    public Optional<SpanRecord> findSpan(String spanId) {
        return Optional.ofNullable(byId.get(spanId));
    }

    @Override
    public List<SpanRecord> recentSpans(SpanType type, int limit) {
        return spans.stream()
                .filter(span -> type == null || span.type() == type)
                .sorted(Comparator.comparing(SpanRecord::startedAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private static boolean matches(SpanRecord span, SpanQuery query) {
        if (query.type() != null && span.type() != query.type()) return false;
        if (query.status() != null && span.status() != query.status()) return false;
        if (query.conversationId() != null && !query.conversationId().isBlank()
                && !query.conversationId().equals(span.conversationId())) return false;
        if (query.taskId() != null && !query.taskId().isBlank()
                && !query.taskId().equals(span.taskId())) return false;
        if (query.requestId() != null && !query.requestId().isBlank()
                && !query.requestId().equals(span.requestId())) return false;
        if (query.agentCode() != null && !query.agentCode().isBlank()
                && !query.agentCode().equals(span.agentCode())) return false;
        if (query.from() != null && span.startedAt().isBefore(query.from())) return false;
        if (query.to() != null && span.startedAt().isAfter(query.to())) return false;
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String keyword = query.keyword().trim().toLowerCase(Locale.ROOT);
            boolean hit = contains(span.id(), keyword)
                    || contains(span.traceId(), keyword)
                    || contains(span.name(), keyword)
                    || contains(span.conversationId(), keyword)
                    || contains(span.taskId(), keyword)
                    || contains(span.requestId(), keyword)
                    || contains(span.agentCode(), keyword)
                    || contains(span.actionCode(), keyword)
                    || contains(span.toolCode(), keyword)
                    || contains(span.modelName(), keyword)
                    || contains(span.errorCode(), keyword);
            if (!hit) return false;
        }
        return true;
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }
}
