package com.manzhushaka.agent.boot.evaluation;

import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import com.manzhushaka.agent.controlplane.evaluation.AgentEvaluationExecutor;
import com.manzhushaka.agent.controlplane.evaluation.EvaluationExecutionContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluationRunOutcome;
import com.manzhushaka.agent.runtime.chat.AgentAppRuntimeContext;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.trace.TraceQueryPort;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Runs an evaluation case through the same ChatOrchestrator chain used by visitor chat and
 * the open Agent API: routing, confirmation gates, idempotency and audits stay on the shared
 * path. Only redacted outcome fields are returned; spans supply token/cost facts.
 */
@Service
public class RuntimeEvaluationExecutor implements AgentEvaluationExecutor {
    private static final long COST_MICROS_PER_TOKEN = 10L;

    private final ChatOrchestrator orchestrator;
    private final AgentApplicationService applicationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final TraceQueryPort traceQueryPort;
    private final TraceRecorder traceRecorder;

    public RuntimeEvaluationExecutor(
            ChatOrchestrator orchestrator,
            AgentApplicationService applicationService,
            KnowledgeBaseService knowledgeBaseService,
            TraceQueryPort traceQueryPort,
            TraceRecorder traceRecorder
    ) {
        this.orchestrator = orchestrator;
        this.applicationService = applicationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.traceQueryPort = traceQueryPort;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public EvaluationRunOutcome execute(EvaluationExecutionContext execution) {
        AgentApplicationService.PublishedAppSnapshot snapshot =
                applicationService.resolvePublishedAppForCall(execution.appCode());
        String content = String.valueOf(execution.input().getOrDefault("text", ""));
        if (content.isBlank()) {
            throw new IllegalArgumentException("用例缺少 input.text");
        }
        String prompt = applicationService.resolvePromptContent(snapshot.promptVersionId(), Map.of());
        String knowledgeContext = retrieveKnowledge(snapshot, content, execution);
        AgentAppRuntimeContext appContext = new AgentAppRuntimeContext(
                snapshot.appCode(), snapshot.appDisplayName(), prompt, knowledgeContext, snapshot.modelCode()
        );
        String conversationId = orchestrator.createConversation(execution.visitorId()).id();
        List<StreamEvent> events = orchestrator.message(
                execution.visitorId(), conversationId, content, execution.requestId(), appContext
        );
        return outcome(events, execution, conversationId);
    }

    private String retrieveKnowledge(
            AgentApplicationService.PublishedAppSnapshot snapshot,
            String content,
            EvaluationExecutionContext execution
    ) {
        String knowledgeBaseId = snapshot.knowledgeBaseId();
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            return "";
        }
        Instant startedAt = Instant.now();
        List<Map<String, Object>> matches;
        try {
            matches = knowledgeBaseService.retrieveForRuntime(knowledgeBaseId, content, 5, 0.35);
            traceRecorder.recordRetrieval(
                    execution.requestId(), execution.visitorId(), null, execution.requestId(),
                    knowledgeBaseId, matches.size(), SpanStatus.OK, null, startedAt, Instant.now()
            );
        } catch (RuntimeException exception) {
            traceRecorder.recordRetrieval(
                    execution.requestId(), execution.visitorId(), null, execution.requestId(),
                    knowledgeBaseId, 0, SpanStatus.ERROR, "RETRIEVAL_FAILED", startedAt, Instant.now()
            );
            throw exception;
        }
        return matches.stream()
                .map(match -> citationText(match))
                .filter(item -> !item.isBlank())
                .limit(6)
                .collect(Collectors.joining("\n"));
    }

    private String citationText(Map<String, Object> match) {
        Object citation = match.get("citation");
        if (!(citation instanceof Map<?, ?> value)) {
            return "";
        }
        Object content = value.get("content");
        return content == null ? "" : String.valueOf(content).trim();
    }

    private EvaluationRunOutcome outcome(
            List<StreamEvent> events,
            EvaluationExecutionContext execution,
            String conversationId
    ) {
        String text = null;
        String routeType = null;
        String routeSource = null;
        String routeAgent = null;
        String actionCode = null;
        String taskId = null;
        Integer confirmationVersion = null;
        boolean confirmationRequired = false;
        List<String> formFields = new ArrayList<>();
        for (StreamEvent event : events) {
            Map<String, Object> payload = event.payload();
            switch (event.type()) {
                case "message.final" -> {
                    Object content = payload.get("content");
                    if (content != null) {
                        text = String.valueOf(content);
                    }
                }
                case "agent.route" -> {
                    routeType = string(payload.get("routeType"));
                    routeSource = string(payload.get("routeSource"));
                    routeAgent = string(payload.get("targetAgentCode"));
                }
                case "form.request" -> {
                    Object fields = payload.get("fields");
                    if (fields instanceof List<?> list) {
                        for (Object field : list) {
                            if (field instanceof Map<?, ?> fieldMap && fieldMap.get("name") != null) {
                                formFields.add(String.valueOf(fieldMap.get("name")));
                            }
                        }
                    }
                }
                case "action.confirm" -> {
                    confirmationRequired = true;
                    taskId = string(payload.get("taskId"));
                    if (payload.get("confirmationVersion") instanceof Number number) {
                        confirmationVersion = number.intValue();
                    }
                }
                case "task.status" -> {
                    if (taskId == null) {
                        taskId = string(payload.get("taskId"));
                    }
                    if (confirmationVersion == null && payload.get("confirmationVersion") instanceof Number number) {
                        confirmationVersion = number.intValue();
                    }
                }
                default -> { }
            }
        }
        List<SpanRecord> spans = traceQueryPort.trace(execution.requestId());
        List<String> toolCodes = spans.stream()
                .filter(span -> span.type() == SpanType.TOOL)
                .map(SpanRecord::toolCode)
                .filter(code -> code != null)
                .distinct()
                .toList();
        if (actionCode == null) {
            actionCode = spans.stream()
                    .filter(span -> span.type() == SpanType.ACTION)
                    .map(SpanRecord::actionCode)
                    .filter(code -> code != null)
                    .findFirst()
                    .orElse(null);
        }
        if (routeAgent == null) {
            routeAgent = spans.stream()
                    .filter(span -> span.type() == SpanType.ROUTE)
                    .map(SpanRecord::agentCode)
                    .filter(code -> code != null)
                    .findFirst()
                    .orElse(null);
        }
        List<String> knowledgeMatchIds = spans.stream()
                .filter(span -> span.type() == SpanType.RETRIEVAL)
                .map(span -> string(span.metadata().get("knowledgeBaseId")))
                .filter(id -> id != null)
                .distinct()
                .map(id -> "kb:" + id)
                .toList();
        int tokens = spans.stream()
                .filter(span -> span.type() == SpanType.MODEL)
                .mapToInt(SpanRecord::totalTokens)
                .sum();
        String errorCode = spans.stream()
                .filter(span -> span.status() == SpanStatus.ERROR)
                .map(SpanRecord::errorCode)
                .filter(code -> code != null)
                .findFirst()
                .orElse(null);
        boolean clarificationRequested = "CLARIFICATION_REQUIRED".equalsIgnoreCase(routeType);
        boolean refused = "UNSUPPORTED".equalsIgnoreCase(routeType);
        return new EvaluationRunOutcome(
                text == null ? "" : text,
                events.stream().map(StreamEvent::type).toList(),
                routeAgent,
                actionCode,
                routeSource,
                routeType,
                formFields,
                clarificationRequested,
                refused,
                taskId != null,
                confirmationRequired,
                confirmationVersion,
                toolCodes,
                knowledgeMatchIds,
                formFields,
                tokens,
                tokens * COST_MICROS_PER_TOKEN,
                errorCode
        );
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }
}
