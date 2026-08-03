package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.common.mask.SensitiveMasker;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Knowledge retrieval node. Uses the workflow-bound knowledge base version and returns masked
 * citations; retrieval failures surface as node errors with a stable error code.
 */
@Component
public class WorkflowRetrievalHandler implements WorkflowNodeHandler {
    private final KnowledgeBaseService knowledgeBaseService;
    private final TraceRecorder traceRecorder;

    public WorkflowRetrievalHandler(KnowledgeBaseService knowledgeBaseService, TraceRecorder traceRecorder) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.RETRIEVAL;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        String boundId = binding(context, "knowledgeBaseVersionId");
        String configuredId = string(context.node().config().get("knowledgeBaseId"));
        String target = boundId.isBlank() ? configuredId : boundId;
        if (target.isBlank()) {
            return WorkflowNodeResult.failed(context.variables(), "KNOWLEDGE_BINDING_MISSING");
        }
        String queryVar = string(context.node().config().get("queryVar"));
        String outputVar = string(context.node().config().get("outputVar"));
        int topK = context.node().config().get("topK") == null
                ? 5 : Integer.parseInt(String.valueOf(context.node().config().get("topK")));
        String query = String.valueOf(context.variables().getOrDefault(queryVar, ""));
        Instant startedAt = Instant.now();
        try {
            List<Map<String, Object>> matches = knowledgeBaseService.retrieveForRuntime(target, query, topK, 0.35);
            traceRecorder.recordRetrieval(context.requestId(), context.visitorRef(), null, context.requestId(),
                    target, matches.size(), SpanStatus.OK, null, startedAt, Instant.now());
            List<Map<String, Object>> citations = matches.stream()
                    .map(match -> masked(match)).limit(6).toList();
            Map<String, Object> variables = new LinkedHashMap<>(context.variables());
            variables.put(outputVar, citations);
            return WorkflowNodeResult.succeeded(Map.of(outputVar, citations), variables, List.of());
        } catch (RuntimeException exception) {
            traceRecorder.recordRetrieval(context.requestId(), context.visitorRef(), null, context.requestId(),
                    target, 0, SpanStatus.ERROR, "RETRIEVAL_FAILED", startedAt, Instant.now());
            return WorkflowNodeResult.failed(context.variables(), "RETRIEVAL_FAILED");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> masked(Map<String, Object> match) {
        Object masked = SensitiveMasker.maskValue(match == null ? Map.of() : match);
        return masked instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static String binding(WorkflowNodeContext context, String key) {
        Object bindingsValue = context.variables().get("_workflowBindings");
        if (bindingsValue instanceof Map<?, ?> bindings) {
            Object value = bindings.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
