package com.manzhushaka.agent.runtime.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.ConfirmationSnapshotHasher;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowExecutionEngineTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void linearRunCompletesWithVariables() {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new AssignHandler()));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-linear", "线性", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("n1", WorkflowNodeType.VARIABLE_ASSIGN, "赋值",
                        Map.of("assignments", Map.of("city", "上海"))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "n1", null),
                new WorkflowEdge("n1", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-linear", dsl, Map.of()
        );

        assertEquals(WorkflowRunStatus.SUCCEEDED, outcome.run().status());
        assertEquals("上海", outcome.run().variables().get("city"));
        assertTrue(outcome.events().stream().anyMatch(event -> "workflow.status".equals(event.type())));
    }

    @Test
    void inputParksRunUntilSubmit() {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new InputHandler()));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-input", "补参", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("in", WorkflowNodeType.INPUT, "输入", Map.of(
                        "fields", List.of(Map.of("name", "city", "label", "城市", "required", true)))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "in", null),
                new WorkflowEdge("in", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome first = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-input", dsl, Map.of()
        );
        assertEquals(WorkflowRunStatus.PAUSED, first.run().status());
        assertEquals("in", first.run().currentNodeId());

        WorkflowExecutionEngine.ExecutionOutcome resumed = engine.submitInput(
                first.run().id(), "wf:admin", "in", Map.of("city", "北京")
        );
        assertEquals(WorkflowRunStatus.SUCCEEDED, resumed.run().status());
        assertEquals("北京", resumed.run().variables().get("city"));
    }

    @Test
    void confirmationParksRunAndStopsWithoutExecution() {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new WriteHandler(store)));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-write", "写节点", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("w", WorkflowNodeType.ACTION, "写操作",
                        Map.of("agentCode", "hotel", "actionCode", "hotel.room.book")),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "w", null),
                new WorkflowEdge("w", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome first = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-write", dsl, Map.of()
        );
        assertEquals(WorkflowRunStatus.PAUSED, first.run().status());
        WorkflowNodeRun nodeRun = store.nodeRuns(first.run().id()).stream()
                .filter(item -> item.nodeId().equals("w")).findFirst().orElseThrow();
        assertEquals(WorkflowNodeRunStatus.WAITING_CONFIRMATION, nodeRun.status());
        assertEquals(0, store.writeAttempts());

        WorkflowExecutionEngine.ExecutionOutcome stopped = engine.stop(first.run().id(), "wf:admin");
        assertEquals(WorkflowRunStatus.STOPPED, stopped.run().status());
        assertEquals(0, store.writeAttempts());
    }

    @Test
    void retryReexecutesFailedNode() {
        FakeStore store = new FakeStore();
        FlakyHandler handler = new FlakyHandler();
        WorkflowExecutionEngine engine = engine(store, List.of(handler));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-flaky", "抖动", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("f", WorkflowNodeType.VARIABLE_ASSIGN, "失败一次",
                        Map.of("assignments", Map.of("x", 1))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "f", null),
                new WorkflowEdge("f", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome first = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-flaky", dsl, Map.of()
        );
        assertEquals(WorkflowRunStatus.FAILED, first.run().status());
        assertEquals(1, handler.attempts());

        WorkflowExecutionEngine.ExecutionOutcome retried = engine.retry(first.run().id(), "wf:admin");
        assertEquals(WorkflowRunStatus.SUCCEEDED, retried.run().status());
        assertEquals(2, handler.attempts());
        WorkflowNodeRun nodeRun = store.nodeRuns(first.run().id()).stream()
                .filter(item -> item.nodeId().equals("f")).findFirst().orElseThrow();
        assertEquals(WorkflowNodeRunStatus.SUCCEEDED, nodeRun.status());
        assertEquals(1, nodeRun.retryCount());
    }

    @Test
    void confirmThenRetryUnknownIsBlocked() {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new UnknownWriteHandler(store)));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-unknown", "结果未知", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("w", WorkflowNodeType.MCP_TOOL, "写工具",
                        Map.of("toolVersionId", "mtv_1", "outputVar", "out")),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "w", null),
                new WorkflowEdge("w", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome first = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-unknown", dsl, Map.of()
        );
        assertEquals(WorkflowRunStatus.PAUSED, first.run().status());

        WorkflowExecutionEngine.ExecutionOutcome failed = engine.confirmNode(
                first.run().id(), "wf:admin", "w", 1, ConfirmationDecision.CONFIRMED
        );
        assertEquals(WorkflowRunStatus.FAILED, failed.run().status());
        assertEquals("RESULT_UNKNOWN", failed.run().errorCode());
        assertThrows(RuntimeException.class,
                () -> engine.retry(failed.run().id(), "wf:admin"));
    }

    @Test
    void classifierRoutesByCondition() {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new ClassifierHandler(), new AssignHandler()));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-classify", "分类", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("c", WorkflowNodeType.CLASSIFIER, "分类", Map.of()),
                new WorkflowNode("yes", WorkflowNodeType.VARIABLE_ASSIGN, "命中",
                        Map.of("assignments", Map.of("branch", "yes"))),
                new WorkflowNode("no", WorkflowNodeType.VARIABLE_ASSIGN, "未命中",
                        Map.of("assignments", Map.of("branch", "no"))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "c", null),
                new WorkflowEdge("c", "yes", "$flag == true"),
                new WorkflowEdge("c", "no", "default"),
                new WorkflowEdge("yes", "end", null),
                new WorkflowEdge("no", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome outcome = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-classify", dsl, Map.of("flag", true)
        );
        assertEquals(WorkflowRunStatus.SUCCEEDED, outcome.run().status());
        assertEquals("yes", outcome.run().variables().get("branch"));
    }


    @Test
    void sweepRestoresInterruptedNodeAndResumeReexecutesIt() throws Exception {
        FakeStore store = new FakeStore();
        WorkflowExecutionEngine engine = engine(store, List.of(new InputHandler()));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-interrupted", "中断", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("in", WorkflowNodeType.INPUT, "输入", Map.of(
                        "fields", List.of(Map.of("name", "city", "label", "城市", "required", true)))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "in", null),
                new WorkflowEdge("in", "end", null)
        ));

        // 模拟进程在节点执行中途崩溃：入边已从 frontier 消费、节点 run 停在 RUNNING、结果未落库。
        Instant now = Instant.now();
        Instant stale = now.minusSeconds(600);
        String runId = "wfl_crash";
        WorkflowRun interrupted = new WorkflowRun(
                runId, "wfo_1", "wfv_1", "wf-interrupted", objectMapper.writeValueAsString(dsl),
                "wfg_1", WorkflowRunStatus.RUNNING, "wf:admin", "wfr_1", Map.of(),
                List.of(), List.of(), List.of(), "in", null, null, null,
                now.minusSeconds(60), null, now.minusSeconds(60), stale
        );
        store.saveRun(interrupted);
        store.saveNodeRun(new WorkflowNodeRun(
                "wfn_crash", runId, "in", WorkflowNodeType.INPUT, WorkflowNodeRunStatus.RUNNING,
                Map.of(), Map.of(), null, 0, null, 0, null,
                now.minusSeconds(60), null, now.minusSeconds(60), stale
        ));

        List<WorkflowRun> swept = engine.sweepStaleRuns();
        assertEquals(1, swept.size());
        WorkflowRun paused = store.findRun(runId).orElseThrow();
        assertEquals(WorkflowRunStatus.PAUSED, paused.status());
        assertEquals("RESTART_RECOVERED", paused.errorCode());
        assertTrue(paused.pendingEdgeKeys().contains("start->in"),
                "sweep 必须恢复被中断节点的入边");
        WorkflowNodeRun interruptedRun = store.findNodeRun(runId, "in").orElseThrow();
        assertEquals(WorkflowNodeRunStatus.FAILED, interruptedRun.status());
        assertEquals("INTERRUPTED", interruptedRun.errorCode());

        // 手动恢复会重新执行被中断的 INPUT 节点并再次停在补参门禁。
        WorkflowExecutionEngine.ExecutionOutcome resumed = engine.resume(runId, "wf:admin");
        assertEquals(WorkflowRunStatus.PAUSED, resumed.run().status());
        assertEquals("in", resumed.run().currentNodeId());
        assertEquals(WorkflowNodeRunStatus.WAITING_INPUT, store.findNodeRun(runId, "in").orElseThrow().status());
    }


    @Test
    void handlerExceptionBecomesFailedNodeAndRetryRecovers() {
        FakeStore store = new FakeStore();
        ThrowOnceHandler handler = new ThrowOnceHandler();
        WorkflowExecutionEngine engine = engine(store, List.of(handler));
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-throw", "抛错", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("t", WorkflowNodeType.VARIABLE_ASSIGN, "抖动",
                        Map.of("assignments", Map.of("x", 1))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "t", null),
                new WorkflowEdge("t", "end", null)
        ));

        WorkflowExecutionEngine.ExecutionOutcome first = engine.start(
                "wf:admin", "req-1", "wfo_1", "wfv_1", "wf-throw", dsl, Map.of()
        );
        // handler 抛运行时异常必须落库为 FAILED，而不是冒泡导致运行停在中间态。
        assertEquals(WorkflowRunStatus.FAILED, first.run().status());
        assertEquals("NODE_ERROR", first.run().errorCode());
        WorkflowNodeRun nodeRun = store.nodeRuns(first.run().id()).stream()
                .filter(item -> item.nodeId().equals("t")).findFirst().orElseThrow();
        assertEquals(WorkflowNodeRunStatus.FAILED, nodeRun.status());
        assertEquals("NODE_ERROR", nodeRun.errorCode());
        assertTrue(first.run().pendingEdgeKeys().contains("start->t"),
                "FAILED 节点必须保留入边供 retry 重新执行");

        WorkflowExecutionEngine.ExecutionOutcome retried = engine.retry(first.run().id(), "wf:admin");
        assertEquals(WorkflowRunStatus.SUCCEEDED, retried.run().status());
        assertEquals(2, handler.attempts());
        WorkflowNodeRun retriedNode = store.nodeRuns(first.run().id()).stream()
                .filter(item -> item.nodeId().equals("t")).findFirst().orElseThrow();
        assertEquals(WorkflowNodeRunStatus.SUCCEEDED, retriedNode.status());
        assertEquals(1, retriedNode.retryCount());
    }

    private static WorkflowExecutionEngine engine(FakeStore store, List<WorkflowNodeHandler> handlers) {
        RuntimeStore runtimeStore = stubRuntimeStore();
        WorkflowConfirmationGate gate = new WorkflowConfirmationGate(
                runtimeStore, new ConfirmationSnapshotHasher(new ObjectMapper()));
        return new WorkflowExecutionEngine(store, gate, new ObjectMapper(), handlers);
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
                            if (args.length >= 2) {
                                return Optional.ofNullable(tasks.get(args[1]));
                            }
                            return Optional.empty();
                        }
                        case "decideConfirmation" -> {
                            AgentTask task = tasks.get(args[1]);
                            if (task == null || task.status() != TaskStatus.WAITING_CONFIRMATION) {
                                return Optional.empty();
                            }
                            ConfirmationDecision decision = (ConfirmationDecision) args[5];
                            task.applyTransition(decision.targetStatus(), TaskTransition.none());
                            return Optional.of(task);
                        }
                        case "transitionTask" -> {
                            AgentTask task = tasks.get(args[1]);
                            if (task == null) {
                                return Optional.empty();
                            }
                            TaskStatus target = (TaskStatus) args[4];
                            task.applyTransition(target, TaskTransition.none());
                            return Optional.of(task);
                        }
                        default -> {
                            return defaultReturn(method.getReturnType());
                        }
                    }
                }
        );
    }

    private static Object defaultReturn(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (List.class.isAssignableFrom(type)) {
            return List.of();
        }
        if (Optional.class.isAssignableFrom(type)) {
            return Optional.empty();
        }
        return null;
    }

    private static final class FakeStore implements WorkflowRunStore {
        private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
        private final Map<String, WorkflowNodeRun> nodeRuns = new ConcurrentHashMap<>();
        private final List<WorkflowEvent> events = new ArrayList<>();
        private int writeAttempts;

        int writeAttempts() {
            return writeAttempts;
        }

        void countWrite() {
            writeAttempts++;
        }

        @Override
        public synchronized WorkflowRun saveRun(WorkflowRun run) {
            runs.put(run.id(), run);
            return run;
        }

        @Override
        public synchronized boolean transitionRunStatus(String runId, WorkflowRunStatus expected, WorkflowRunStatus target) {
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

    private static final class AssignHandler implements WorkflowNodeHandler {
        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.VARIABLE_ASSIGN;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            Map<String, Object> variables = new LinkedHashMap<>(context.variables());
            Object assignments = context.node().config().get("assignments");
            if (assignments instanceof Map<?, ?> map) {
                map.forEach((key, value) -> variables.put(String.valueOf(key), value));
            }
            return WorkflowNodeResult.succeeded(Map.of(), variables, List.of());
        }
    }

    private static final class ClassifierHandler implements WorkflowNodeHandler {
        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.CLASSIFIER;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            return WorkflowNodeResult.succeeded(Map.of(), context.variables(), List.of());
        }
    }


    private static final class ThrowOnceHandler implements WorkflowNodeHandler {
        private int attempts;

        int attempts() {
            return attempts;
        }

        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.VARIABLE_ASSIGN;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            attempts++;
            if (attempts == 1) {
                throw new IllegalStateException("模拟节点运行时故障");
            }
            Map<String, Object> variables = new LinkedHashMap<>(context.variables());
            variables.put("recovered", true);
            return WorkflowNodeResult.succeeded(Map.of(), variables, List.of());
        }
    }

    private static final class FlakyHandler implements WorkflowNodeHandler {
        private int attempts;

        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.VARIABLE_ASSIGN;
        }

        int attempts() {
            return attempts;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            attempts++;
            WorkflowNodeRun nodeRun = context.runStore().findNodeRun(context.run().id(), context.node().id())
                    .orElse(null);
            if (nodeRun == null || nodeRun.retryCount() == 0) {
                return WorkflowNodeResult.failed(context.variables(), "TRANSIENT");
            }
            return WorkflowNodeResult.succeeded(Map.of("ok", true), context.variables(), List.of());
        }
    }

    private static final class InputHandler implements WorkflowNodeHandler {
        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.INPUT;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            List<Map<String, Object>> fields = new ArrayList<>();
            fields.add(Map.of("name", "city", "label", "城市", "required", true));
            return WorkflowNodeResult.waitingInput(context.variables(), fields, List.of());
        }
    }

    private static final class WriteHandler implements WorkflowNodeHandler {
        private final FakeStore store;

        WriteHandler(FakeStore store) {
            this.store = store;
        }

        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.ACTION;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            WorkflowNodeRun nodeRun = context.runStore().findNodeRun(context.run().id(), context.node().id())
                    .orElse(null);
            if (nodeRun == null || nodeRun.confirmationTaskId() == null) {
                WorkflowTaskPreparation preparation = context.confirmationGate().prepare(
                        context.visitorRef(), context.run().id(), context.node().id(),
                        context.requestId(), "hotel.room.book", Map.of("roomId", "1")
                );
                return WorkflowNodeResult.waitingConfirmation(
                        context.variables(), preparation.taskId(), preparation.confirmationVersion(),
                        preparation.confirmationSnapshotHash(), List.of());
            }
            context.confirmationGate().confirmedTask(context.visitorRef(), nodeRun.confirmationTaskId());
            store.countWrite();
            return WorkflowNodeResult.succeeded(Map.of("booked", true), context.variables(), List.of());
        }
    }

    private static final class UnknownWriteHandler implements WorkflowNodeHandler {
        private final FakeStore store;

        UnknownWriteHandler(FakeStore store) {
            this.store = store;
        }

        @Override
        public boolean supports(WorkflowNodeType type) {
            return type == WorkflowNodeType.MCP_TOOL;
        }

        @Override
        public WorkflowNodeResult execute(WorkflowNodeContext context) {
            WorkflowNodeRun nodeRun = context.runStore().findNodeRun(context.run().id(), context.node().id())
                    .orElse(null);
            if (nodeRun == null || nodeRun.confirmationTaskId() == null) {
                WorkflowTaskPreparation preparation = context.confirmationGate().prepare(
                        context.visitorRef(), context.run().id(), context.node().id(),
                        context.requestId(), "mcp.write", Map.of("x", 1)
                );
                return WorkflowNodeResult.waitingConfirmation(
                        context.variables(), preparation.taskId(), preparation.confirmationVersion(),
                        preparation.confirmationSnapshotHash(), List.of());
            }
            AgentTask task = context.confirmationGate().confirmedTask(
                    context.visitorRef(), nodeRun.confirmationTaskId());
            context.confirmationGate().failConfirmed(
                    context.visitorRef(), task.id(), task.version(), "RESULT_UNKNOWN");
            return WorkflowNodeResult.failed(context.variables(), "RESULT_UNKNOWN");
        }
    }
}
