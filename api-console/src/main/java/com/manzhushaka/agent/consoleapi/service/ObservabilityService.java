package com.manzhushaka.agent.consoleapi.service;

import com.manzhushaka.agent.consoleapi.dto.ObservabilityOverviewResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.dto.TraceSpanResponse;
import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.trace.TraceQueryPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Console observability aggregation over spans, tasks and confirmation decisions. */
@Service
public class ObservabilityService {
    private static final int METRIC_SPAN_LIMIT = 5000;

    private final TraceQueryPort traceQueryPort;
    private final TraceStore traceStore;
    private final RuntimeStore runtimeStore;

    public ObservabilityService(
            TraceQueryPort traceQueryPort,
            ObjectProvider<TraceStore> traceStore,
            RuntimeStore runtimeStore
    ) {
        this.traceQueryPort = traceQueryPort;
        this.traceStore = traceStore.getIfAvailable(() -> null);
        this.runtimeStore = runtimeStore;
    }

    public PageResponse<TraceSpanResponse> traces(
            int page,
            int size,
            String type,
            String status,
            String query
    ) {
        SpanQuery spanQuery = new SpanQuery(
                page, size, parseType(type), parseStatus(status), null, null, null, null,
                query, null, null
        );
        SpanPage source = traceQueryPort.query(spanQuery);
        List<TraceSpanResponse> items = source.items().stream().map(this::span).toList();
        long total = source.total();
        int pageSize = Math.max(1, Math.min(size, 100));
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(items, page, pageSize, total, totalPages);
    }

    public Map<String, Object> trace(String traceId) {
        List<SpanRecord> spans = traceQueryPort.trace(traceId);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", traceId);
        summary.put("spanCount", spans.size());
        summary.put("conversationIds", spans.stream().map(SpanRecord::conversationId)
                .filter(value -> value != null).distinct().toList());
        summary.put("taskIds", spans.stream().map(SpanRecord::taskId)
                .filter(value -> value != null).distinct().toList());
        summary.put("requestIds", spans.stream().map(SpanRecord::requestId)
                .filter(value -> value != null).distinct().toList());
        summary.put("agentCodes", spans.stream().map(SpanRecord::agentCode)
                .filter(value -> value != null).distinct().toList());
        summary.put("actionCodes", spans.stream().map(SpanRecord::actionCode)
                .filter(value -> value != null).distinct().toList());
        summary.put("toolCodes", spans.stream().map(SpanRecord::toolCode)
                .filter(value -> value != null).distinct().toList());
        summary.put("modelNames", spans.stream().map(SpanRecord::modelName)
                .filter(value -> value != null).distinct().toList());
        summary.put("totalTokens", spans.stream().mapToInt(SpanRecord::totalTokens).sum());
        summary.put("totalDurationMs", spans.stream().mapToLong(SpanRecord::durationMs).sum());
        summary.put("spans", spans.stream().map(this::span).toList());
        return Map.copyOf(summary);
    }

    public ObservabilityOverviewResponse overview() {
        List<SpanRecord> modelSpans = traceQueryPort.recent(SpanType.MODEL, METRIC_SPAN_LIMIT);
        List<SpanRecord> toolSpans = traceQueryPort.recent(SpanType.TOOL, METRIC_SPAN_LIMIT);
        List<SpanRecord> requestSpans = traceQueryPort.recent(SpanType.REQUEST, METRIC_SPAN_LIMIT);
        List<SpanRecord> allSpans = traceStore == null
                ? List.of()
                : traceStore.recentSpans(null, METRIC_SPAN_LIMIT);
        Map<String, Long> typeDistribution = allSpans.stream()
                .collect(Collectors.groupingBy(span -> span.type().name(), Collectors.counting()));
        Map<String, Long> statusDistribution = allSpans.stream()
                .collect(Collectors.groupingBy(span -> span.status().name(), Collectors.counting()));
        List<SpanRecord> taskSpans = allSpans.stream()
                .filter(span -> span.type() == SpanType.TASK)
                .toList();
        long confirmationCreated = taskSpans.stream()
                .filter(span -> "task.confirmation".equals(span.name())).count();
        long confirmationConfirmed = taskSpans.stream()
                .filter(span -> "task.confirm".equals(span.name()))
                .filter(span -> "CONFIRMED".equals(span.metadata().get("decision"))).count();
        long confirmationRejected = taskSpans.stream()
                .filter(span -> "task.confirm".equals(span.name()))
                .filter(span -> "REJECTED".equals(span.metadata().get("decision"))).count();
        double confirmationRate = confirmationCreated == 0 ? 0.0
                : (double) (confirmationConfirmed + confirmationRejected) / confirmationCreated;
        List<AgentTask> tasks = runtimeStore == null ? List.of() : runtimeStore.listTasks();
        Map<String, Long> taskStatusDistribution = tasks.stream()
                .collect(Collectors.groupingBy(task -> task.status().name(), Collectors.counting()));
        long unknownTasks = tasks.stream()
                .filter(task -> task.status() == TaskStatus.UNKNOWN || task.status() == TaskStatus.MANUAL)
                .count();
        long timeoutTasks = tasks.stream()
                .filter(task -> task.nextRecoveryAt() != null || task.lastErrorCode() != null)
                .count();
        return new ObservabilityOverviewResponse(
                requestSpans.size(),
                allSpans.size(),
                typeDistribution,
                statusDistribution,
                modelMetrics(modelSpans),
                toolMetrics(toolSpans),
                taskMetrics(
                        taskStatusDistribution, confirmationCreated, confirmationConfirmed,
                        confirmationRejected, confirmationRate, unknownTasks, timeoutTasks
                )
        );
    }

    private ObservabilityOverviewResponse.ModelMetricsResponse modelMetrics(List<SpanRecord> spans) {
        long avg = spans.isEmpty() ? 0 : spans.stream().mapToLong(SpanRecord::durationMs).sum() / spans.size();
        return new ObservabilityOverviewResponse.ModelMetricsResponse(
                spans.size(),
                avg,
                percentile(spans, 0.95),
                spans.stream().mapToLong(SpanRecord::totalTokens).sum(),
                spans.stream().filter(span -> span.status() == SpanStatus.ERROR).count(),
                spans.stream().filter(span -> span.status() == SpanStatus.TIMEOUT).count()
        );
    }

    private ObservabilityOverviewResponse.ToolMetricsResponse toolMetrics(List<SpanRecord> spans) {
        long avg = spans.isEmpty() ? 0 : spans.stream().mapToLong(SpanRecord::durationMs).sum() / spans.size();
        return new ObservabilityOverviewResponse.ToolMetricsResponse(
                spans.size(),
                avg,
                spans.stream().filter(span -> span.status() == SpanStatus.ERROR).count(),
                spans.stream().filter(span -> span.status() == SpanStatus.UNKNOWN).count()
        );
    }

    private ObservabilityOverviewResponse.TaskMetricsResponse taskMetrics(
            Map<String, Long> statusDistribution,
            long confirmationCreated,
            long confirmationConfirmed,
            long confirmationRejected,
            double confirmationRate,
            long unknownTasks,
            long timeoutTasks
    ) {
        return new ObservabilityOverviewResponse.TaskMetricsResponse(
                statusDistribution, confirmationCreated, confirmationConfirmed, confirmationRejected,
                Math.round(confirmationRate * 10000.0) / 10000.0, unknownTasks, timeoutTasks
        );
    }

    private TraceSpanResponse span(SpanRecord span) {
        return new TraceSpanResponse(
                span.id(), span.traceId(), span.type().name(), span.name(), span.status().name(),
                span.visitorRef(), span.conversationId(), span.taskId(), span.requestId(),
                span.agentCode(), span.actionCode(), span.toolCode(), span.modelProvider(),
                span.modelName(), span.inputTokens(), span.outputTokens(), span.totalTokens(),
                span.durationMs(), span.errorCode(), span.resourceVersions(), span.metadata(),
                span.startedAt(), span.finishedAt()
        );
    }

    private static long percentile(List<SpanRecord> spans, double percentile) {
        if (spans.isEmpty()) {
            return 0;
        }
        List<Long> sorted = spans.stream().map(SpanRecord::durationMs).sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(Math.max(0, index));
    }

    private static SpanType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpanType.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static SpanStatus parseStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpanStatus.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
