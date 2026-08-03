package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.workflow.WorkflowManagementService;
import com.manzhushaka.agent.controlplane.workflow.WorkflowRunService;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Console API for Workflow Studio. Every write and run operation enforces workflow:write /
 * workflow:run through the control-plane services; the SSE endpoint only exposes read events.
 */
@RestController
@RequestMapping("/api/console/v1")
public class WorkflowController {
    private static final long SSE_MAX_TICKS = 300;

    private final ConsoleAuthenticationService authenticationService;
    private final WorkflowManagementService managementService;
    private final WorkflowRunService runService;
    private final WorkflowRunStore runStore;

    public WorkflowController(
            ConsoleAuthenticationService authenticationService,
            WorkflowManagementService managementService,
            WorkflowRunService runService,
            WorkflowRunStore runStore
    ) {
        this.authenticationService = authenticationService;
        this.managementService = managementService;
        this.runService = runService;
        this.runStore = runStore;
    }

    @GetMapping("/workflows")
    public Map<String, Object> workflows(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return managementService.workflows(principal(authorization), keyword,
                Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    @PostMapping("/workflows")
    public Map<String, Object> createWorkflow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return managementService.createWorkflow(principal(authorization), request);
    }

    @GetMapping("/workflows/{id}")
    public Map<String, Object> workflow(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.workflow(principal(authorization), id);
    }

    @PutMapping("/workflows/{id}")
    public Map<String, Object> updateWorkflow(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return managementService.updateWorkflow(principal(authorization), id, request);
    }

    @PostMapping("/workflows/{id}:archive")
    public Map<String, Object> archiveWorkflow(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.archive(principal(authorization), id);
    }

    @PostMapping("/workflows/{id}:rollback")
    public Map<String, Object> rollbackWorkflow(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.rollback(principal(authorization), id);
    }

    @PostMapping("/workflows/{id}/versions")
    public Map<String, Object> createVersion(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return managementService.createVersion(principal(authorization), id, request);
    }

    @GetMapping("/workflows/{id}/versions")
    public List<Map<String, Object>> versions(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.versions(principal(authorization), id);
    }

    @GetMapping("/workflow-versions/{versionId}")
    public Map<String, Object> version(
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.version(principal(authorization), versionId);
    }

    @PostMapping("/workflow-versions/{versionId}:validate")
    public Map<String, Object> validateVersion(
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.validateVersion(principal(authorization), versionId);
    }

    @PostMapping("/workflow-versions/{versionId}:publish")
    public Map<String, Object> publishVersion(
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return managementService.publishVersion(principal(authorization), versionId);
    }

    @PostMapping("/workflow-runs")
    public Map<String, Object> startRun(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        String versionId = String.valueOf(request.getOrDefault("versionId", ""));
        Object variables = request.get("initialVariables");
        return runService.startRun(principal(authorization), versionId,
                variables instanceof Map<?, ?> map ? mapStringMap(map) : Map.of());
    }

    @GetMapping("/workflow-runs")
    public Map<String, Object> runs(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return runService.runs(principal(authorization), keyword, status,
                Math.max(1, page), Math.min(100, Math.max(1, size)));
    }

    @GetMapping("/workflow-runs/{runId}")
    public Map<String, Object> run(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return runService.run(principal(authorization), runId);
    }

    @PostMapping("/workflow-runs/{runId}:resume")
    public Map<String, Object> resume(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return runService.resume(principal(authorization), runId);
    }

    @PostMapping("/workflow-runs/{runId}:input")
    public Map<String, Object> submitInput(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        String nodeId = String.valueOf(request.getOrDefault("nodeId", ""));
        Object values = request.get("values");
        return runService.submitInput(principal(authorization), runId, nodeId,
                values instanceof Map<?, ?> map ? mapStringMap(map) : Map.of());
    }

    @PostMapping("/workflow-runs/{runId}:confirm")
    public Map<String, Object> confirm(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        String nodeId = String.valueOf(request.getOrDefault("nodeId", ""));
        int confirmationVersion = request.get("confirmationVersion") instanceof Number number
                ? number.intValue() : 0;
        String decision = String.valueOf(request.getOrDefault("decision", ""));
        return runService.confirmNode(principal(authorization), runId, nodeId,
                confirmationVersion, decision);
    }

    @PostMapping("/workflow-runs/{runId}:stop")
    public Map<String, Object> stop(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return runService.stop(principal(authorization), runId);
    }

    @PostMapping("/workflow-runs/{runId}:retry")
    public Map<String, Object> retry(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return runService.retry(principal(authorization), runId);
    }

    @GetMapping("/workflow-runs/{runId}/node-runs")
    public List<Map<String, Object>> nodeRuns(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return runService.nodeRuns(principal(authorization), runId);
    }

    @GetMapping(value = "/workflow-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> events(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestParam(defaultValue = "120") long timeoutSeconds
    ) {
        ControlPlanePrincipal principal = authenticationService.requirePermission(
                authorization, WorkflowManagementService.WORKFLOW_READ);
        runStore.findRun(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
        AtomicLong lastSequence = new AtomicLong(Math.max(0, afterSequence));
        AtomicBoolean closed = new AtomicBoolean(false);
        long maxTicks = Math.max(10, Math.min(timeoutSeconds, SSE_MAX_TICKS));
        return Flux.interval(Duration.ofSeconds(1))
                .takeWhile(tick -> !closed.get())
                .take(maxTicks + 2)
                .concatMap(tick -> Flux.defer(() -> {
                    WorkflowRun run = runStore.findRun(runId).orElse(null);
                    if (run == null) {
                        closed.set(true);
                        return Flux.empty();
                    }
                    boolean terminal = run.status() == WorkflowRunStatus.SUCCEEDED
                            || run.status() == WorkflowRunStatus.FAILED
                            || run.status() == WorkflowRunStatus.STOPPED;
                    List<Map<String, Object>> batch = runService.events(
                            principal, runId, lastSequence.get(), 200);
                    if (!batch.isEmpty()) {
                        lastSequence.set(((Number) batch.getLast().get("sequence")).longValue());
                    }
                    if (terminal && batch.isEmpty()) {
                        closed.set(true);
                    }
                    List<ServerSentEvent<Map<String, Object>>> events = new ArrayList<>();
                    for (Map<String, Object> event : batch) {
                        events.add(ServerSentEvent.builder(event)
                                .id(String.valueOf(event.get("sequence")))
                                .event("workflow.event")
                                .build());
                    }
                    return Flux.fromIterable(events);
                }));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapStringMap(Map<?, ?> source) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private ControlPlanePrincipal principal(String authorization) {
        return authenticationService.requirePrincipal(authorization);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        HttpStatus status = switch (exception.code()) {
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("code", exception.code().name(), "message", exception.getMessage()));
    }
}
