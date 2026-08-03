package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.ConfirmationSnapshotHasher;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import com.manzhushaka.agent.runtime.workflow.WorkflowDsl;
import com.manzhushaka.agent.runtime.workflow.WorkflowEdge;
import com.manzhushaka.agent.runtime.workflow.WorkflowEvent;
import com.manzhushaka.agent.runtime.workflow.WorkflowExecutionEngine;
import com.manzhushaka.agent.runtime.workflow.WorkflowNode;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import com.manzhushaka.agent.runtime.workflow.WorkflowConfirmationGate;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunPage;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRunServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InMemoryWorkflowRepository repository = new InMemoryWorkflowRepository();
    private final FakeWorkflowRunStore runStore = new FakeWorkflowRunStore();
    private final ControlPlaneService controlPlaneService = new ControlPlaneService();
    private final WorkflowRunService service = runService();

    private WorkflowRunService runService() {
        RuntimeStore runtimeStore = stubRuntimeStore();
        WorkflowConfirmationGate gate = new WorkflowConfirmationGate(
                runtimeStore, new ConfirmationSnapshotHasher(new ObjectMapper()));
        WorkflowExecutionEngine engine = new WorkflowExecutionEngine(
                runStore, gate, new ObjectMapper(), List.of(new InputHandler()));
        return new WorkflowRunService(engine, repository, runStore, objectMapper);
    }

    private ControlPlanePrincipal principal(String username, String role) {
        return controlPlaneService.principal(username, role);
    }

    @Test
    void operatorStartsDraftDebugRunAndSubmitsInput() {
        seedInputWorkflow();
        ControlPlanePrincipal operator = principal("op", "OPERATOR");
        Map<String, Object> started = service.startRun(operator, "wfv_1", Map.of());
        assertEquals(WorkflowRunStatus.PAUSED, started.get("status"));
        assertEquals("wf-input", started.get("code"));
        assertEquals("in", started.get("currentNodeId"));
        String runId = (String) started.get("id");

        Map<String, Object> finished = service.submitInput(operator, runId, "in",
                Map.of("city", "北京"));
        assertEquals(WorkflowRunStatus.SUCCEEDED, finished.get("status"));
        assertEquals("北京", ((Map<?, ?>) finished.get("variables")).get("city"));
    }

    @Test
    void runOwnershipIsolatedBetweenAdmins() {
        seedInputWorkflow();
        Map<String, Object> started = service.startRun(principal("alice", "ADMIN"), "wfv_1", Map.of());
        assertEquals(WorkflowRunStatus.PAUSED, started.get("status"));
        String runId = (String) started.get("id");

        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.resume(principal("bob", "ADMIN"), runId));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.confirmNode(principal("bob", "ADMIN"), runId, "in", 1, "CONFIRMED"));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.stop(principal("bob", "ADMIN"), runId));

        Map<String, Object> resumed = service.resume(principal("alice", "ADMIN"), runId);
        assertEquals(WorkflowRunStatus.PAUSED, resumed.get("status"));
    }

    @Test
    void viewerCanReadButCannotRunOrStop() {
        seedInputWorkflow();
        Map<String, Object> started = service.startRun(principal("op", "OPERATOR"), "wfv_1", Map.of());
        String runId = (String) started.get("id");

        ControlPlanePrincipal viewer = principal("viewer", "VIEWER");
        Map<String, Object> view = service.run(viewer, runId);
        assertEquals(WorkflowRunStatus.PAUSED, view.get("status"));
        assertEquals(1, ((List<?>) service.runs(viewer, null, null, 1, 20).get("items")).size());
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.startRun(viewer, "wfv_1", Map.of()));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.stop(viewer, runId));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.submitInput(viewer, runId, "in", Map.of()));
    }

    @Test
    void publishedLinearRunSucceedsAndStopsAreIdempotentGuarded() {
        String dslJson = dslJson(linearDsl());
        repository.saveWorkflow(new WorkflowDefinition("wfo_2", "wf-linear", "线性", null,
                "ACTIVE", "wfv_2", "admin", Instant.now(), Instant.now()));
        repository.saveVersion(new WorkflowVersion("wfv_2", "wfo_2", 1, "PUBLISHED", "1.0",
                dslJson, Map.of(), "v1", "admin", Instant.now(), Instant.now(), Instant.now()));
        ControlPlanePrincipal operator = principal("op", "OPERATOR");

        Map<String, Object> outcome = service.startRun(operator, "wfv_2", Map.of());
        assertEquals(WorkflowRunStatus.SUCCEEDED, outcome.get("status"));
        String runId = (String) outcome.get("id");
        assertThrows(RuntimeException.class, () -> service.stop(operator, runId));
        assertTrue(service.events(operator, runId, 0, 100).size() >= 1);
    }

    private void seedInputWorkflow() {
        repository.saveWorkflow(new WorkflowDefinition("wfo_1", "wf-input", "补参", null,
                "DRAFT", null, "admin", Instant.now(), Instant.now()));
        repository.saveVersion(new WorkflowVersion("wfv_1", "wfo_1", 1, "DRAFT", "1.0",
                dslJson(inputDsl(true)), Map.of(), "v1", "admin", null, Instant.now(), Instant.now()));
    }

    private WorkflowDsl inputDsl(boolean withInput) {
        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()));
        if (withInput) {
            nodes.add(new WorkflowNode("in", WorkflowNodeType.INPUT, "输入",
                    Map.of("fields", List.of(Map.of("name", "city", "label", "城市", "required", true)))));
        }
        nodes.add(new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of()));
        List<WorkflowEdge> edges = new ArrayList<>();
        edges.add(new WorkflowEdge("start", "in", null));
        edges.add(new WorkflowEdge("in", "end", null));
        return new WorkflowDsl("1.0", "wf-input", "补参", nodes, edges);
    }

    private WorkflowDsl linearDsl() {
        return new WorkflowDsl("1.0", "wf-linear", "线性", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "end", null)
        ));
    }

    private String dslJson(WorkflowDsl dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static RuntimeStore stubRuntimeStore() {
        Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
        return (RuntimeStore) Proxy.newProxyInstance(
                RuntimeStore.class.getClassLoader(),
                new Class<?>[]{RuntimeStore.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "saveTask" -> {
                            AgentTask task = (AgentTask) args[0];
                            tasks.put(task.id(), task);
                            return task;
                        }
                        case "findTask" -> {
                            return Optional.ofNullable(tasks.get(args[1]));
                        }
                        case "decideConfirmation" -> {
                            AgentTask task = tasks.get(args[1]);
                            if (task == null || task.status() != TaskStatus.WAITING_CONFIRMATION) {
                                return Optional.empty();
                            }
                            task.applyTransition(((ConfirmationDecision) args[5]).targetStatus(),
                                    TaskTransition.none());
                            return Optional.of(task);
                        }
                        case "transitionTask" -> {
                            AgentTask task = tasks.get(args[1]);
                            if (task == null) {
                                return Optional.empty();
                            }
                            task.applyTransition((TaskStatus) args[4], TaskTransition.none());
                            return Optional.of(task);
                        }
                        default -> {
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) {
                                return false;
                            }
                            if (type == int.class) {
                                return 0;
                            }
                            if (List.class.isAssignableFrom(type)) {
                                return List.of();
                            }
                            if (Optional.class.isAssignableFrom(type)) {
                                return Optional.empty();
                            }
                            return null;
                        }
                    }
                }
        );
    }

    private static final class FakeWorkflowRunStore implements WorkflowRunStore {
        private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
        private final Map<String, WorkflowNodeRun> nodeRuns = new ConcurrentHashMap<>();
        private final List<WorkflowEvent> events = new ArrayList<>();

        @Override
        public synchronized WorkflowRun saveRun(WorkflowRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public synchronized boolean transitionRunStatus(String runId, WorkflowRunStatus expected,
                                                        WorkflowRunStatus target) {
            WorkflowRun current = runs.get(runId);
            if (current == null || current.status() != expected) {
                return false;
            }
            runs.put(runId, current.withState(target, current.variables(), current.visitedNodeIds(),
                    current.visitedEdgeKeys(), current.pendingEdgeKeys(), current.currentNodeId(),
                    current.errorCode()));
            return true;
        }

        @Override
        public Optional<WorkflowRun> findRun(String runId) {
            return Optional.ofNullable(runs.get(runId));
        }

        @Override
        public Optional<WorkflowRun> findRunForVisitor(String visitorRef, String runId) {
            return findRun(runId).filter(run -> run.visitorRef().equals(visitorRef));
        }

        @Override
        public WorkflowRunPage listRuns(String keyword, String status, int page, int size) {
            return new WorkflowRunPage(List.copyOf(runs.values()), runs.size());
        }

        @Override
        public List<WorkflowRun> findStaleRunning(Instant now, Duration lease) {
            return runs.values().stream()
                    .filter(run -> run.status() == WorkflowRunStatus.RUNNING)
                    .filter(run -> run.updatedAt().isBefore(now.minus(lease)))
                    .toList();
        }

        @Override
        public WorkflowNodeRun saveNodeRun(WorkflowNodeRun nodeRun) {
            nodeRuns.put(nodeRun.runId() + ":" + nodeRun.nodeId(), nodeRun);
            return nodeRun;
        }

        @Override
        public Optional<WorkflowNodeRun> findNodeRun(String runId, String nodeId) {
            return Optional.ofNullable(nodeRuns.get(runId + ":" + nodeId));
        }

        @Override
        public List<WorkflowNodeRun> nodeRuns(String runId) {
            return nodeRuns.values().stream().filter(item -> item.runId().equals(runId)).toList();
        }

        @Override
        public synchronized void saveEvent(WorkflowEvent event) {
            events.add(event);
        }

        @Override
        public synchronized List<WorkflowEvent> events(String runId, long afterSequence, int limit) {
            return events.stream().filter(event -> event.runId().equals(runId)
                    && event.sequence() > afterSequence).limit(limit).toList();
        }
    }

    private static final class InputHandler implements WorkflowNodeHandler {
        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.INPUT;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            return WorkflowNodeResult.waitingInput(context.variables(), List.of(
                    Map.of("name", "city", "label", "城市", "required", true)), List.of());
        }
    }
}
