package com.manzhushaka.agent.runtime.trace;

import com.manzhushaka.agent.common.mask.SensitiveMasker;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Records completed spans into the durable trace store. All metadata is masked and prompt or
 * message content is never accepted. Trace identity is the request id when present, otherwise
 * a generated id, so a task detail page can link to its full trace by request id.
 */
@Service
public class TraceRecorder {
    private final TraceStore traceStore;
    private final boolean enabled;

    public TraceRecorder(
            ObjectProvider<TraceStore> traceStore,
            @Value("${agent.trace.enabled:true}") boolean enabled
    ) {
        this.traceStore = traceStore.getIfAvailable(() -> null);
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public String traceIdFor(String requestId) {
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        return "trc_" + UUID.randomUUID();
    }

    public void recordRequest(
            String traceId,
            String visitorId,
            String conversationId,
            String requestId,
            String agentCode,
            Instant startedAt,
            Instant finishedAt,
            SpanStatus status,
            String errorCode,
            Map<String, Object> resourceVersions
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.REQUEST, "chat.message", status,
                visitorRef(visitorId), conversationId, null, requestId, agentCode,
                null, null, null, null, 0, 0, 0,
                durationMs(startedAt, finishedAt), errorCode,
                resourceVersions, Map.of(), startedAt, finishedAt, Instant.now()
        ));
    }

    public void recordRoute(
            String traceId,
            String visitorId,
            String conversationId,
            String requestId,
            String agentCode,
            String routeType,
            String routeSource,
            Instant at
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.ROUTE, "chat.route", SpanStatus.OK,
                visitorRef(visitorId), conversationId, null, requestId, agentCode,
                null, null, null, null, 0, 0, 0, 0, null,
                Map.of(), Map.of("routeType", routeType, "routeSource", routeSource),
                at, at, Instant.now()
        ));
    }

    public void recordTask(
            String traceId,
            String visitorId,
            AgentTask task,
            SpanStatus status,
            String name,
            Map<String, Object> metadata,
            Instant at
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.TASK, name, status,
                visitorRef(visitorId), task.conversationId(), task.id(), traceId,
                task.domainCode(), task.actionCode(), null, null, null, 0, 0, 0,
                durationMs(task.createdAt(), at), task.lastErrorCode(), Map.of(), metadata,
                task.createdAt(), at, Instant.now()
        ));
    }

    public void recordAction(
            String traceId,
            String visitorId,
            String conversationId,
            String requestId,
            String agentCode,
            String actionCode,
            String mode,
            int missingFieldCount,
            Instant at
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.ACTION, "action.select", SpanStatus.OK,
                visitorRef(visitorId), conversationId, null, requestId, agentCode,
                actionCode, null, null, null, 0, 0, 0, 0, null,
                Map.of(), Map.of("mode", mode, "missingFields", missingFieldCount),
                at, at, Instant.now()
        ));
    }

    public void recordTool(
            String traceId,
            String visitorId,
            String conversationId,
            String taskId,
            String requestId,
            String toolCode,
            SpanStatus status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.TOOL, "tool." + toolCode, status,
                visitorRef(visitorId), conversationId, taskId, requestId, null, null,
                toolCode, null, null, 0, 0, 0, durationMs(startedAt, finishedAt),
                errorCode, Map.of(), Map.of(), startedAt, finishedAt, Instant.now()
        ));
    }

    public void recordModel(
            String traceId,
            String visitorId,
            String conversationId,
            String requestId,
            String provider,
            String modelName,
            int promptTokens,
            int completionTokens,
            SpanStatus status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt
    ) {
        int total = promptTokens + completionTokens;
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.MODEL, "model.call", status,
                visitorRef(visitorId), conversationId, null, requestId, null, null,
                null, provider, modelName, promptTokens, completionTokens, total,
                durationMs(startedAt, finishedAt), errorCode, Map.of(), Map.of(),
                startedAt, finishedAt, Instant.now()
        ));
    }

    public void recordRetrieval(
            String traceId,
            String visitorId,
            String conversationId,
            String requestId,
            String knowledgeBaseId,
            int matchCount,
            SpanStatus status,
            String errorCode,
            Instant startedAt,
            Instant finishedAt
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.RETRIEVAL, "knowledge.retrieve", status,
                visitorRef(visitorId), conversationId, null, requestId, null, null,
                null, null, null, 0, 0, 0, durationMs(startedAt, finishedAt),
                errorCode, Map.of(), Map.of("knowledgeBaseId", knowledgeBaseId, "matchCount", matchCount),
                startedAt, finishedAt, Instant.now()
        ));
    }

    public void recordEvaluation(
            String traceId,
            String visitorId,
            String experimentId,
            String caseKey,
            SpanStatus status,
            String errorCode,
            Map<String, Object> metadata,
            Instant startedAt,
            Instant finishedAt
    ) {
        record(new SpanRecord(
                spanId(), traceId, null, SpanType.EVALUATION, "evaluation.case", status,
                visitorRef(visitorId), null, null, traceId, null, null, null,
                null, null, 0, 0, 0, durationMs(startedAt, finishedAt),
                errorCode, Map.of(), masked(metadata), startedAt, finishedAt, Instant.now()
        ));
    }

    private void record(SpanRecord span) {
        if (!enabled || traceStore == null) {
            return;
        }
        traceStore.saveSpan(span);
    }

    private static String spanId() {
        return "spn_" + UUID.randomUUID();
    }

    static String visitorRef(String visitorId) {
        if (visitorId == null || visitorId.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(visitorId.getBytes(StandardCharsets.UTF_8));
            return "visitor_" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static long durationMs(Instant startedAt, Instant finishedAt) {
        if (startedAt == null || finishedAt == null) {
            return 0;
        }
        return Math.max(0, java.time.Duration.between(startedAt, finishedAt).toMillis());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> masked(Map<String, Object> metadata) {
        Object masked = SensitiveMasker.maskValue(metadata == null ? Map.of() : metadata);
        return masked instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
