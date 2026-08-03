package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.common.mask.SensitiveMasker;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.workflow.WorkflowDsl;
import com.manzhushaka.agent.runtime.workflow.WorkflowEvent;
import com.manzhushaka.agent.runtime.workflow.WorkflowExecutionEngine;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunPage;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow run orchestration for the Console. Runs are owned by the initiating admin principal
 * (visitorRef = wf:&lt;username&gt;); every resume/confirm/stop/retry re-validates that ownership.
 * Debug runs may use DRAFT versions; publish still requires the full static and binding validation.
 */
@Service
public class WorkflowRunService {
    private final WorkflowExecutionEngine engine;
    private final WorkflowRepository repository;
    private final WorkflowRunStore runStore;
    private final ObjectMapper objectMapper;

    public WorkflowRunService(
            WorkflowExecutionEngine engine,
            WorkflowRepository repository,
            WorkflowRunStore runStore,
            ObjectMapper objectMapper
    ) {
        this.engine = engine;
        this.repository = repository;
        this.runStore = runStore;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> startRun(
            ControlPlanePrincipal principal,
            String versionId,
            Map<String, Object> initialVariables
    ) {
        requireRun(principal);
        WorkflowVersion version = requireVersion(versionId);
        WorkflowDsl dsl = parseDsl(version.dslJson());
        String visitorRef = "wf:" + principal.username();
        String requestId = "wfr_" + UUID.randomUUID();
        Map<String, Object> variables = new LinkedHashMap<>(
                initialVariables == null ? Map.of() : initialVariables);
        variables.put("_workflowBindings", version.resourceBindings());
        String code = repository.workflow(version.workflowId())
                .map(WorkflowDefinition::code)
                .orElse(version.workflowId());
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.start(
                visitorRef, requestId, version.workflowId(), version.id(), code,
                dsl, variables
        );
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> resume(ControlPlanePrincipal principal, String runId) {
        requireRun(principal);
        WorkflowRun run = requireOwned(principal, runId);
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.resume(run.id(), run.visitorRef());
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> submitInput(
            ControlPlanePrincipal principal,
            String runId,
            String nodeId,
            Map<String, Object> values
    ) {
        requireRun(principal);
        WorkflowRun run = requireOwned(principal, runId);
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.submitInput(
                run.id(), run.visitorRef(), nodeId, values == null ? Map.of() : values
        );
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> confirmNode(
            ControlPlanePrincipal principal,
            String runId,
            String nodeId,
            int confirmationVersion,
            String decision
    ) {
        requireRun(principal);
        WorkflowRun run = requireOwned(principal, runId);
        ConfirmationDecision parsed;
        try {
            parsed = ConfirmationDecision.valueOf(decision == null ? "" : decision.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("decision 必须是 CONFIRMED/REJECTED/EXPIRED");
        }
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.confirmNode(
                run.id(), run.visitorRef(), nodeId, confirmationVersion, parsed
        );
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> stop(ControlPlanePrincipal principal, String runId) {
        requireRun(principal);
        WorkflowRun run = requireOwned(principal, runId);
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.stop(run.id(), run.visitorRef());
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> retry(ControlPlanePrincipal principal, String runId) {
        requireRun(principal);
        WorkflowRun run = requireOwned(principal, runId);
        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.retry(run.id(), run.visitorRef());
        return runView(outcome.run(), outcome.events());
    }

    public Map<String, Object> run(ControlPlanePrincipal principal, String runId) {
        requireRead(principal);
        WorkflowRun run = runStore.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
        return runView(run, List.of());
    }

    public Map<String, Object> runs(ControlPlanePrincipal principal, String keyword, String status, int page, int size) {
        requireRead(principal);
        WorkflowRunPage source = runStore.listRuns(keyword, status, page, size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", source.items().stream().map(run -> runView(run, List.of())).toList());
        result.put("total", source.total());
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public List<Map<String, Object>> nodeRuns(ControlPlanePrincipal principal, String runId) {
        requireRead(principal);
        runStore.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
        return runStore.nodeRuns(runId).stream().map(this::nodeRunView).toList();
    }

    public List<Map<String, Object>> events(ControlPlanePrincipal principal, String runId, long afterSequence, int limit) {
        requireRead(principal);
        runStore.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
        return runStore.events(runId, afterSequence, Math.max(1, Math.min(limit, 200))).stream()
                .map(this::eventView).toList();
    }

    private WorkflowRun requireOwned(ControlPlanePrincipal principal, String runId) {
        WorkflowRun run = runStore.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
        if (!("wf:" + principal.username()).equals(run.visitorRef())) {
            throw new ControlPlaneAccessDeniedException();
        }
        return run;
    }

    private WorkflowVersion requireVersion(String id) {
        return repository.version(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow 版本不存在: " + id));
    }

    private WorkflowDsl parseDsl(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDsl.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Workflow 版本 DSL 无法解析", exception);
        }
    }

    private Map<String, Object> runView(WorkflowRun run, List<WorkflowEvent> newEvents) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id());
        view.put("workflowId", run.workflowId());
        view.put("workflowVersionId", run.workflowVersionId());
        view.put("code", run.code());
        view.put("graphThreadId", run.graphThreadId());
        view.put("status", run.status());
        view.put("requestId", run.requestId());
        view.put("variables", mask(run.variables()));
        view.put("visitedNodeIds", run.visitedNodeIds());
        view.put("currentNodeId", run.currentNodeId());
        view.put("errorCode", run.errorCode());
        view.put("startedAt", run.startedAt());
        view.put("finishedAt", run.finishedAt());
        view.put("createdAt", run.createdAt());
        view.put("updatedAt", run.updatedAt());
        view.put("events", newEvents.stream().map(this::eventView).toList());
        return view;
    }

    private Map<String, Object> nodeRunView(WorkflowNodeRun nodeRun) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", nodeRun.id());
        view.put("nodeId", nodeRun.nodeId());
        view.put("nodeType", nodeRun.nodeType());
        view.put("status", nodeRun.status());
        view.put("input", mask(nodeRun.input()));
        view.put("output", mask(nodeRun.output()));
        view.put("confirmationTaskId", nodeRun.confirmationTaskId());
        view.put("confirmationVersion", nodeRun.confirmationVersion());
        view.put("retryCount", nodeRun.retryCount());
        view.put("errorCode", nodeRun.errorCode());
        view.put("startedAt", nodeRun.startedAt());
        view.put("finishedAt", nodeRun.finishedAt());
        return view;
    }

    private Map<String, Object> eventView(WorkflowEvent event) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", event.id());
        view.put("sequence", event.sequence());
        view.put("type", event.type());
        view.put("nodeId", event.nodeId());
        view.put("payload", mask(event.payload()));
        view.put("createdAt", event.createdAt());
        return view;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mask(Map<String, Object> value) {
        Object masked = SensitiveMasker.maskValue(value == null ? Map.of() : value);
        return masked instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void requireRead(ControlPlanePrincipal principal) {
        if (principal == null || !principal.permissions().contains(WorkflowManagementService.WORKFLOW_READ)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }

    private static void requireRun(ControlPlanePrincipal principal) {
        if (principal == null || !principal.permissions().contains(WorkflowManagementService.WORKFLOW_RUN)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }
}
