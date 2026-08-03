package com.manzhushaka.agent.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic workflow execution engine. It walks a validated DAG, checkpointing every node into
 * the run store. Write nodes park in WAITING_CONFIRMATION through {@link WorkflowConfirmationGate}
 * and execute only after a confirmed decision; INPUT nodes park in WAITING_INPUT until the owner
 * submits values. Pause/resume/stop/retry operate on the persisted frontier, so a restart never
 * re-executes an already SUCCEEDED node.
 */
@Service
public class WorkflowExecutionEngine {
    private static final int MAX_STEPS_PER_TICK = 2000;
    private static final int EVENT_PAGE_LIMIT = 1000;
    private static final Duration STALE_LEASE = Duration.ofMinutes(2);

    private final WorkflowRunStore store;
    private final WorkflowConfirmationGate gate;
    private final ObjectMapper objectMapper;
    private final Map<WorkflowNodeType, WorkflowNodeHandler> handlers;

    public WorkflowExecutionEngine(
            WorkflowRunStore store,
            WorkflowConfirmationGate gate,
            ObjectMapper objectMapper,
            List<WorkflowNodeHandler> handlerBeans
    ) {
        this.store = store;
        this.gate = gate;
        this.objectMapper = objectMapper;
        Map<WorkflowNodeType, WorkflowNodeHandler> index = new LinkedHashMap<>();
        for (WorkflowNodeHandler handler : handlerBeans) {
            for (WorkflowNodeType type : WorkflowNodeType.values()) {
                if (handler.supports(type)) {
                    index.put(type, handler);
                }
            }
        }
        this.handlers = Map.copyOf(index);
    }

    public record ExecutionOutcome(WorkflowRun run, List<WorkflowEvent> events) {
        public ExecutionOutcome {
            events = List.copyOf(events);
        }
    }

    public ExecutionOutcome start(
            String visitorRef,
            String requestId,
            String workflowId,
            String workflowVersionId,
            String code,
            WorkflowDsl dsl,
            Map<String, Object> initialVariables
    ) {
        WorkflowCompiledGraph graph = WorkflowDslValidator.validate(dsl);
        String runId = "wfl_" + UUID.randomUUID();
        Instant now = Instant.now();
        WorkflowRun run = new WorkflowRun(
                runId, workflowId, workflowVersionId, code, dslJson(dsl), "wfg_" + UUID.randomUUID(),
                WorkflowRunStatus.RUNNING, visitorRef, requestId, initialVariables,
                List.of(), List.of(), List.copyOf(pushOutgoing(graph, graph.startNodeId(), initialVariables)),
                graph.startNodeId(), null, null, null, now, null, now, now
        );
        store.saveRun(run);
        return tick(run, graph);
    }

    public ExecutionOutcome resume(String runId, String visitorRef) {
        WorkflowRun run = requireOwnedRun(runId, visitorRef);
        if (run.status() != WorkflowRunStatus.PAUSED && run.status() != WorkflowRunStatus.RUNNING) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态不允许恢复: " + run.status());
        }
        if (run.status() == WorkflowRunStatus.PAUSED
                && !store.transitionRunStatus(runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已变化，不能恢复。");
        }
        WorkflowCompiledGraph graph = graphOf(run);
        WorkflowRun running = run.withState(
                WorkflowRunStatus.RUNNING, run.variables(), run.visitedNodeIds(),
                run.visitedEdgeKeys(), run.pendingEdgeKeys(), run.currentNodeId(), null
        );
        store.saveRun(running);
        return tick(running, graph);
    }

    public ExecutionOutcome submitInput(
            String runId,
            String visitorRef,
            String nodeId,
            Map<String, Object> values
    ) {
        WorkflowRun run = requireOwnedRun(runId, visitorRef);
        if (run.status() != WorkflowRunStatus.PAUSED || !nodeId.equals(run.currentNodeId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行没有在该节点等待输入。");
        }
        if (!store.transitionRunStatus(runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已变化，不能提交输入。");
        }
        WorkflowNodeRun nodeRun = store.findNodeRun(runId, nodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "节点运行不存在"));
        if (nodeRun.status() != WorkflowNodeRunStatus.WAITING_INPUT) {
            throw new BusinessException(ErrorCode.CONFLICT, "节点没有等待输入。");
        }
        Map<String, Object> variables = new LinkedHashMap<>(run.variables());
        variables.putAll(values);
        List<String> pending = new ArrayList<>(run.pendingEdgeKeys());
        String incomingEdge = pending.isEmpty() ? null : pending.removeFirst();
        List<String> visitedEdges = new ArrayList<>(run.visitedEdgeKeys());
        if (incomingEdge != null && !visitedEdges.contains(incomingEdge)) {
            visitedEdges.add(incomingEdge);
        }
        List<String> visitedNodes = new ArrayList<>(run.visitedNodeIds());
        visitedNodes.add(nodeId);
        store.saveNodeRun(nodeRun.withOutput(values).withStatus(WorkflowNodeRunStatus.SUCCEEDED));
        WorkflowCompiledGraph graph = graphOf(run);
        pending.addAll(pushOutgoing(graph, nodeId, variables));
        WorkflowRun updated = run.withState(
                WorkflowRunStatus.RUNNING, variables, visitedNodes, visitedEdges, pending, nodeId, null
        );
        store.saveRun(updated);
        return tick(updated, graph);
    }

    public ExecutionOutcome confirmNode(
            String runId,
            String visitorRef,
            String nodeId,
            int confirmationVersion,
            ConfirmationDecision decision
    ) {
        WorkflowRun run = requireOwnedRun(runId, visitorRef);
        if (run.status() != WorkflowRunStatus.PAUSED || !nodeId.equals(run.currentNodeId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行没有在该节点等待确认。");
        }
        if (!store.transitionRunStatus(runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已变化，不能确认。");
        }
        WorkflowNodeRun nodeRun = store.findNodeRun(runId, nodeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "节点运行不存在"));
        if (nodeRun.status() != WorkflowNodeRunStatus.WAITING_CONFIRMATION
                || nodeRun.confirmationTaskId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "节点没有等待确认。");
        }
        AgentTask decided = gate.decide(
                visitorRef,
                nodeRun.confirmationTaskId(),
                confirmationVersion,
                nodeRun.confirmationSnapshotHash(),
                decision,
                run.requestId()
        );
        if (decision == ConfirmationDecision.REJECTED || decision == ConfirmationDecision.EXPIRED) {
            String errorCode = "CONFIRMATION_" + decision.name();
            store.saveNodeRun(nodeRun.withError(errorCode));
            WorkflowRun failed = run.withState(
                    WorkflowRunStatus.FAILED, run.variables(), run.visitedNodeIds(),
                    run.visitedEdgeKeys(), run.pendingEdgeKeys(), nodeId, errorCode
            );
            store.saveRun(failed);
            return emit(new ExecutionOutcome(failed, List.of(workflowNodeEvent(failed, nodeId,
                    WorkflowNodeRunStatus.FAILED, errorCode))));
        }
        store.saveNodeRun(nodeRun.withStatus(WorkflowNodeRunStatus.RUNNING));
        WorkflowRun confirmed = run.withState(
                WorkflowRunStatus.RUNNING, run.variables(), run.visitedNodeIds(),
                run.visitedEdgeKeys(), run.pendingEdgeKeys(), nodeId, null
        );
        store.saveRun(confirmed);
        return tick(confirmed, graphOf(confirmed));
    }

    public ExecutionOutcome stop(String runId, String visitorRef) {
        WorkflowRun run = requireOwnedRun(runId, visitorRef);
        if (run.status() == WorkflowRunStatus.SUCCEEDED || run.status() == WorkflowRunStatus.FAILED
                || run.status() == WorkflowRunStatus.STOPPED) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行已结束，不能停止。");
        }
        boolean statusChanged = store.transitionRunStatus(runId, WorkflowRunStatus.RUNNING, WorkflowRunStatus.STOPPED)
                || store.transitionRunStatus(runId, WorkflowRunStatus.PAUSED, WorkflowRunStatus.STOPPED);
        if (!statusChanged) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已变化，不能停止。");
        }
        WorkflowNodeRun nodeRun = run.currentNodeId() == null ? null
                : store.findNodeRun(runId, run.currentNodeId()).orElse(null);
        if (nodeRun != null && nodeRun.status() == WorkflowNodeRunStatus.WAITING_CONFIRMATION
                && nodeRun.confirmationTaskId() != null) {
            gate.cancelPending(visitorRef, nodeRun.confirmationTaskId());
        }
        WorkflowRun stopped = run.withState(
                WorkflowRunStatus.STOPPED, run.variables(), run.visitedNodeIds(),
                run.visitedEdgeKeys(), run.pendingEdgeKeys(), run.currentNodeId(), "STOPPED"
        );
        store.saveRun(stopped);
        return emit(new ExecutionOutcome(stopped, List.of(
                workflowStatusEvent(stopped, WorkflowRunStatus.STOPPED, "STOPPED"))));
    }

    public ExecutionOutcome retry(String runId, String visitorRef) {
        WorkflowRun run = requireOwnedRun(runId, visitorRef);
        if (run.status() != WorkflowRunStatus.FAILED) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的运行可以重试。");
        }
        if (!store.transitionRunStatus(runId, WorkflowRunStatus.FAILED, WorkflowRunStatus.RUNNING)) {
            throw new BusinessException(ErrorCode.CONFLICT, "运行状态已变化，不能重试。");
        }
        List<WorkflowNodeRun> failedNodes = store.nodeRuns(runId).stream()
                .filter(nodeRun -> nodeRun.status() == WorkflowNodeRunStatus.FAILED)
                .toList();
        boolean resultUnknown = failedNodes.stream()
                .anyMatch(nodeRun -> "RESULT_UNKNOWN".equals(nodeRun.errorCode()));
        if (resultUnknown) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "存在结果未知节点，禁止自动重试，请先人工核对外部结果。");
        }
        for (WorkflowNodeRun failed : failedNodes) {
            store.saveNodeRun(failed.reset(failed.retryCount() + 1));
        }
        WorkflowRun retried = run.withState(
                WorkflowRunStatus.RUNNING, run.variables(), run.visitedNodeIds(),
                run.visitedEdgeKeys(), run.pendingEdgeKeys(), run.currentNodeId(), null
        );
        store.saveRun(retried);
        return tick(retried, graphOf(retried));
    }

    /** Startup sweep: runs left RUNNING by a crashed process become PAUSED for manual resume. */
    public List<WorkflowRun> sweepStaleRuns() {
        Instant now = Instant.now();
        List<WorkflowRun> stale = store.findStaleRunning(now, STALE_LEASE);
        for (WorkflowRun run : stale) {
            // A crash can interrupt a node after its incoming edge was consumed but before the
            // result was persisted: the node run stays RUNNING and the frontier loses that edge.
            // Mark the interrupted node FAILED(INTERRUPTED) and restore its incoming edge, so a
            // manual resume re-executes the node through the normal gate; RESULT_UNKNOWN nodes
            // still block retry by the existing guard.
            List<String> pending = new ArrayList<>(run.pendingEdgeKeys());
            String interrupted = run.currentNodeId();
            if (interrupted != null) {
                for (WorkflowNodeRun nodeRun : store.nodeRuns(run.id())) {
                    if (nodeRun.status() == WorkflowNodeRunStatus.RUNNING
                            && nodeRun.nodeId().equals(interrupted)) {
                        store.saveNodeRun(nodeRun.withError("INTERRUPTED"));
                    }
                }
                WorkflowCompiledGraph graph = graphOf(run);
                String restored = graph.incoming().getOrDefault(interrupted, List.of()).stream()
                        // incoming 值为源节点 id，按引擎边键约定拼成 "<from>-><to>"。
                        .map(source -> source + "->" + interrupted)
                        .filter(edgeKey -> !run.visitedEdgeKeys().contains(edgeKey)
                                && !pending.contains(edgeKey))
                        .findFirst()
                        .orElse(null);
                if (restored != null) {
                    pending.addFirst(restored);
                }
            }
            if (!store.transitionRunStatus(run.id(), WorkflowRunStatus.RUNNING, WorkflowRunStatus.PAUSED)) {
                continue;
            }
            WorkflowRun paused = run.withState(
                    WorkflowRunStatus.PAUSED, run.variables(), run.visitedNodeIds(),
                    run.visitedEdgeKeys(), pending, run.currentNodeId(), "RESTART_RECOVERED"
            );
            store.saveRun(paused);
        }
        return stale;
    }

    private ExecutionOutcome tick(WorkflowRun startRun, WorkflowCompiledGraph graph) {
        List<WorkflowEvent> events = new ArrayList<>();
        WorkflowRun current = startRun;
        long sequence = store.events(current.id(), 0, EVENT_PAGE_LIMIT).size();
        int steps = 0;
        while (steps++ < MAX_STEPS_PER_TICK) {
            if (current.status() != WorkflowRunStatus.RUNNING) {
                break;
            }
            List<String> pending = new ArrayList<>(current.pendingEdgeKeys());
            if (pending.isEmpty()) {
                boolean reachedEnd = current.visitedNodeIds().stream()
                        .map(graph::node).anyMatch(node -> node.type() == WorkflowNodeType.END);
                if (reachedEnd) {
                    WorkflowRun finished = current.withState(
                            WorkflowRunStatus.SUCCEEDED, current.variables(), current.visitedNodeIds(),
                            current.visitedEdgeKeys(), List.of(), current.currentNodeId(), null
                    );
                    store.saveRun(finished);
                    events.add(workflowStatusEvent(finished, WorkflowRunStatus.SUCCEEDED, null));
                    current = finished;
                } else {
                    WorkflowRun failed = current.withState(
                            WorkflowRunStatus.FAILED, current.variables(), current.visitedNodeIds(),
                            current.visitedEdgeKeys(), List.of(), current.currentNodeId(), "NO_EDGE_LEFT"
                    );
                    store.saveRun(failed);
                    events.add(workflowStatusEvent(failed, WorkflowRunStatus.FAILED, "NO_EDGE_LEFT"));
                    current = failed;
                }
                break;
            }
            String edgeKey = pending.removeFirst();
            WorkflowEdge edge = graph.edge(edgeKey);
            WorkflowNode target = graph.node(edge.to());
            if (current.visitedNodeIds().contains(target.id())) {
                List<String> visitedEdges = new ArrayList<>(current.visitedEdgeKeys());
                if (!visitedEdges.contains(edgeKey)) {
                    visitedEdges.add(edgeKey);
                }
                List<String> merged = new ArrayList<>(pending);
                for (String next : pushOutgoing(graph, target.id(), current.variables())) {
                    if (!visitedEdges.contains(next) && !merged.contains(next)) {
                        merged.add(next);
                    }
                }
                current = current.withState(
                        WorkflowRunStatus.RUNNING, current.variables(), current.visitedNodeIds(),
                        visitedEdges, merged, target.id(), null
                );
                store.saveRun(current);
                continue;
            }
            if (target.type() == WorkflowNodeType.END) {
                List<String> visitedNodes = new ArrayList<>(current.visitedNodeIds());
                visitedNodes.add(target.id());
                List<String> visitedEdges = new ArrayList<>(current.visitedEdgeKeys());
                visitedEdges.add(edgeKey);
                store.saveNodeRun(new WorkflowNodeRun(
                        "wfn_" + UUID.randomUUID(), current.id(), target.id(), target.type(),
                        WorkflowNodeRunStatus.SUCCEEDED, Map.of(), Map.of(), null, 0, null,
                        0, null, Instant.now(), Instant.now(), Instant.now(), Instant.now()
                ));
                WorkflowRun finished = current.withState(
                        WorkflowRunStatus.SUCCEEDED, current.variables(), visitedNodes,
                        visitedEdges, List.of(), target.id(), null
                );
                store.saveRun(finished);
                events.add(workflowNodeEvent(finished, target.id(), WorkflowNodeRunStatus.SUCCEEDED, null));
                events.add(workflowStatusEvent(finished, WorkflowRunStatus.SUCCEEDED, null));
                current = finished;
                break;
            }
            WorkflowNodeRun existing = store.findNodeRun(current.id(), target.id()).orElse(null);
            WorkflowNodeRun nodeRun = new WorkflowNodeRun(
                    existing == null ? "wfn_" + UUID.randomUUID() : existing.id(),
                    current.id(), target.id(), target.type(), WorkflowNodeRunStatus.RUNNING,
                    Map.copyOf(current.variables()), Map.of(),
                    existing == null ? null : existing.confirmationTaskId(),
                    existing == null ? 0 : existing.confirmationVersion(),
                    existing == null ? null : existing.confirmationSnapshotHash(),
                    existing == null ? 0 : existing.retryCount(),
                    null, existing == null ? Instant.now() : existing.startedAt(), null,
                    existing == null ? Instant.now() : existing.createdAt(), Instant.now()
            );
            store.saveNodeRun(nodeRun);
            WorkflowRun executing = current.withState(
                    WorkflowRunStatus.RUNNING, current.variables(), current.visitedNodeIds(),
                    current.visitedEdgeKeys(), pending, target.id(), null
            );
            store.saveRun(executing);
            WorkflowNodeHandler handler = handlerFor(target.type());
            WorkflowNodeContext context = new WorkflowNodeContext(
                    executing, target, executing.variables(), executing.visitorRef(),
                    executing.requestId(), gate, store
            );
            WorkflowNodeResult result;
            try {
                result = handler.execute(context);
            } catch (BusinessException exception) {
                // 确定性失败：节点 FAILED 并落库稳定错误码，避免异常在请求线程冒泡导致
                // 运行停留在 RUNNING/节点 RUNNING 的中间态。写节点外部结果未知必须由
                // handler 返回 RESULT_UNKNOWN，而不是抛异常，保证 retry 门禁语义不变。
                result = WorkflowNodeResult.failed(context.variables(),
                        exception.code() == null ? "NODE_ERROR" : exception.code().name());
            } catch (RuntimeException exception) {
                result = WorkflowNodeResult.failed(context.variables(), "NODE_ERROR");
            }
            for (StreamEvent streamEvent : result.events()) {
                events.add(streamToWorkflowEvent(streamEvent, current.id(), current.visitorRef()));
            }
            WorkflowNodeRun attempted = store.findNodeRun(current.id(), target.id()).orElse(nodeRun);
            switch (result.status()) {
                case SUCCEEDED -> {
                    Map<String, Object> variables = new LinkedHashMap<>(result.variables());
                    List<String> visitedNodes = new ArrayList<>(current.visitedNodeIds());
                    visitedNodes.add(target.id());
                    List<String> visitedEdges = new ArrayList<>(current.visitedEdgeKeys());
                    if (!visitedEdges.contains(edgeKey)) {
                        visitedEdges.add(edgeKey);
                    }
                    store.saveNodeRun(attempted.withOutput(result.output())
                            .withStatus(WorkflowNodeRunStatus.SUCCEEDED));
                    List<String> merged = new ArrayList<>(pending);
                    for (String next : pushOutgoing(graph, target.id(), variables)) {
                        if (!visitedEdges.contains(next) && !merged.contains(next)) {
                            merged.add(next);
                        }
                    }
                    current = current.withState(
                            WorkflowRunStatus.RUNNING, variables, visitedNodes, visitedEdges,
                            merged, target.id(), null
                    );
                    store.saveRun(current);
                    events.add(workflowNodeEvent(current, target.id(), WorkflowNodeRunStatus.SUCCEEDED, null));
                }
                case WAITING_INPUT -> {
                    store.saveNodeRun(attempted.withStatus(WorkflowNodeRunStatus.WAITING_INPUT));
                    List<String> parked = new ArrayList<>(pending);
                    parked.addFirst(edgeKey);
                    current = current.withState(
                            WorkflowRunStatus.PAUSED, result.variables(), current.visitedNodeIds(),
                            current.visitedEdgeKeys(), parked, target.id(), null
                    );
                    store.saveRun(current);
                    events.add(workflowNodeEvent(current, target.id(), WorkflowNodeRunStatus.WAITING_INPUT, null));
                    events.add(formRequestEvent(current, target.id(), result.inputFields()));
                    break;
                }
                case WAITING_CONFIRMATION -> {
                    store.saveNodeRun(attempted.withConfirmation(
                            result.confirmationTaskId(), result.confirmationVersion(),
                            result.confirmationSnapshotHash()
                    ));
                    List<String> parked = new ArrayList<>(pending);
                    parked.addFirst(edgeKey);
                    current = current.withState(
                            WorkflowRunStatus.PAUSED, result.variables(), current.visitedNodeIds(),
                            current.visitedEdgeKeys(), parked, target.id(), null
                    );
                    store.saveRun(current);
                    events.add(workflowNodeEvent(current, target.id(),
                            WorkflowNodeRunStatus.WAITING_CONFIRMATION, null));
                    events.add(confirmRequestEvent(current, target.id(),
                            result.confirmationTaskId(), result.confirmationVersion()));
                    break;
                }
                case FAILED -> {
                    store.saveNodeRun(attempted.withError(result.errorCode()));
                    List<String> parked = new ArrayList<>(pending);
                    parked.addFirst(edgeKey);
                    WorkflowRun failed = current.withState(
                            WorkflowRunStatus.FAILED, result.variables(), current.visitedNodeIds(),
                            current.visitedEdgeKeys(), parked, target.id(), result.errorCode()
                    );
                    store.saveRun(failed);
                    events.add(workflowNodeEvent(failed, target.id(), WorkflowNodeRunStatus.FAILED, result.errorCode()));
                    events.add(workflowStatusEvent(failed, WorkflowRunStatus.FAILED, result.errorCode()));
                    current = failed;
                    break;
                }
                case PENDING, RUNNING, SKIPPED ->
                        throw new IllegalStateException("节点返回了非法状态: " + result.status());
            }
        }
        if (current.status() == WorkflowRunStatus.RUNNING && steps >= MAX_STEPS_PER_TICK) {
            WorkflowRun guarded = current.withState(
                    WorkflowRunStatus.PAUSED, current.variables(), current.visitedNodeIds(),
                    current.visitedEdgeKeys(), current.pendingEdgeKeys(), current.currentNodeId(),
                    "STEP_LIMIT"
            );
            store.saveRun(guarded);
            current = guarded;
        }
        return emit(new ExecutionOutcome(current, events));
    }

    private ExecutionOutcome emit(ExecutionOutcome outcome) {
        long sequence = store.events(outcome.run().id(), 0, EVENT_PAGE_LIMIT).size();
        List<WorkflowEvent> persisted = new ArrayList<>();
        for (WorkflowEvent event : outcome.events()) {
            WorkflowEvent saved = new WorkflowEvent(
                    "wfe_" + UUID.randomUUID(), event.runId(), ++sequence, event.type(),
                    event.nodeId(), event.payload(), event.createdAt()
            );
            store.saveEvent(saved);
            persisted.add(saved);
        }
        return new ExecutionOutcome(outcome.run(), persisted);
    }

    private WorkflowNodeHandler handlerFor(WorkflowNodeType type) {
        WorkflowNodeHandler handler = handlers.get(type);
        if (handler == null) {
            throw new IllegalStateException("没有注册的 Workflow 节点处理器: " + type);
        }
        return handler;
    }

    private WorkflowCompiledGraph graphOf(WorkflowRun run) {
        try {
            WorkflowDsl dsl = objectMapper.readValue(run.dslJson(), WorkflowDsl.class);
            return WorkflowDslValidator.validate(dsl);
        } catch (Exception exception) {
            throw new IllegalStateException("工作流运行 DSL 无法解析: " + run.id(), exception);
        }
    }

    private String dslJson(WorkflowDsl dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Workflow DSL 无法序列化", exception);
        }
    }

    private WorkflowRun requireOwnedRun(String runId, String visitorRef) {
        return store.findRunForVisitor(visitorRef, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "工作流运行不存在"));
    }

    private static List<String> pushOutgoing(
            WorkflowCompiledGraph graph,
            String nodeId,
            Map<String, Object> variables
    ) {
        List<WorkflowEdge> edges = graph.edgesFrom(nodeId);
        if (edges.size() == 1) {
            return List.of(edges.getFirst().key());
        }
        WorkflowNode node = graph.node(nodeId);
        if (node.type() == WorkflowNodeType.PARALLEL) {
            return edges.stream().map(WorkflowEdge::key).toList();
        }
        for (WorkflowEdge edge : edges) {
            if ("default".equals(edge.condition())) {
                continue;
            }
            if (WorkflowCondition.evaluate(edge.condition(), variables)) {
                return List.of(edge.key());
            }
        }
        return edges.stream().filter(edge -> "default".equals(edge.condition()))
                .map(WorkflowEdge::key).toList();
    }

    private static WorkflowEvent workflowStatusEvent(
            WorkflowRun run,
            WorkflowRunStatus status,
            String errorCode
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.name());
        payload.put("runId", run.id());
        payload.put("errorCode", errorCode);
        return event(run, "workflow.status", run.currentNodeId(), payload);
    }

    private static WorkflowEvent workflowNodeEvent(
            WorkflowRun run,
            String nodeId,
            WorkflowNodeRunStatus status,
            String errorCode
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("status", status.name());
        payload.put("runId", run.id());
        payload.put("errorCode", errorCode);
        return event(run, "workflow.node", nodeId, payload);
    }

    private static WorkflowEvent formRequestEvent(WorkflowRun run, String nodeId, List<Map<String, Object>> fields) {
        return event(run, "form.request", nodeId, Map.of(
                "runId", run.id(),
                "nodeId", nodeId,
                "fields", fields
        ));
    }

    private static WorkflowEvent confirmRequestEvent(
            WorkflowRun run,
            String nodeId,
            String taskId,
            int confirmationVersion
    ) {
        return event(run, "action.confirm", nodeId, Map.of(
                "runId", run.id(),
                "nodeId", nodeId,
                "taskId", taskId,
                "confirmationVersion", confirmationVersion
        ));
    }

    private static WorkflowEvent event(
            WorkflowRun run,
            String type,
            String nodeId,
            Map<String, Object> payload
    ) {
        return new WorkflowEvent(
                "wfe_" + UUID.randomUUID(), run.id(), 0, type, nodeId, payload, Instant.now()
        );
    }

    private static WorkflowEvent streamToWorkflowEvent(StreamEvent streamEvent, String runId, String visitorRef) {
        Map<String, Object> payload = new LinkedHashMap<>(streamEvent.payload());
        payload.putIfAbsent("runId", runId);
        return new WorkflowEvent(
                "wfe_" + UUID.randomUUID(), runId, 0, streamEvent.type(), null, payload,
                streamEvent.timestamp()
        );
    }
}
