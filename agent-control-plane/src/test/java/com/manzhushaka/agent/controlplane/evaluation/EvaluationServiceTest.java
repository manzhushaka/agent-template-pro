package com.manzhushaka.agent.controlplane.evaluation;

import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.evaluation.plugin.IntentRouteEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationServiceTest {
    private final InMemoryEvaluationRepository repository = new InMemoryEvaluationRepository();
    private final ControlPlaneService controlPlaneService = new ControlPlaneService();
    private final AgentApplicationService agentApplicationService = mock(AgentApplicationService.class);
    private final ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
    private final ControlPlanePrincipal viewer = controlPlaneService.principal("viewer", "VIEWER");
    private EvaluationService service;
    private String datasetVersionId;

    @BeforeEach
    void setUp() {
        when(agentApplicationService.version(any(), any())).thenReturn(Map.of("id", "av-1", "status", "PUBLISHED"));
        when(agentApplicationService.appCodeForVersion("av-1")).thenReturn("customer-assistant");
        service = new EvaluationService(repository, agentApplicationService,
                List.of(new IntentRouteEvaluator()), stubExecutor());
    }

    @Test
    void datasetLifecycleCreatesVersionsCasesAndEnforcesWritePermission() {
        Map<String, Object> dataset = service.createDataset(admin, Map.of(
                "code", "hotel-eval", "displayName", "酒店评估"
        ));
        assertNotNull(dataset.get("currentVersionId"));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.createDataset(viewer, Map.of("code", "x", "displayName", "x")));

        List<Map<String, Object>> versions = service.datasetVersions(admin, String.valueOf(dataset.get("id")));
        assertEquals(1, versions.size());
        String versionId = String.valueOf(versions.getFirst().get("id"));

        Map<String, Object> created = service.addCase(admin, versionId, Map.of(
                "caseKey", "case-1", "category", "intent-route",
                "input", Map.of("text", "帮我查一下上海酒店房态"),
                "expected", Map.of("agentCode", "hotel", "actionCode", "hotel.room.search")
        ));
        assertEquals("case-1", created.get("caseKey"));

        long imported = service.importCases(admin, versionId, List.of(
                Map.of("caseKey", "case-2", "category", "confirmation-gate",
                        "input", Map.of("text", "取消我的订单"), "expected", Map.of("confirmationVersion", 1)),
                Map.of("caseKey", "case-3", "category", "deny",
                        "input", Map.of("text", "删除别人订单"), "expected", Map.of())
        ));
        assertEquals(2, imported);
        EvalPage<Map<String, Object>> page = service.cases(admin, versionId, null, null, 1, 10);
        assertEquals(3, page.total());
    }

    @Test
    void evaluatorLifecycleCreatesVersionedEvaluator() {
        Map<String, Object> evaluator = service.createEvaluator(admin, Map.of(
                "code", "intent-route-check", "displayName", "意图路由",
                "evaluatorType", "INTENT_ROUTE", "config", Map.of()
        ));
        Map<String, Object> version = service.createEvaluatorVersion(admin,
                String.valueOf(evaluator.get("id")), Map.of("config", Map.of("agentCode", "hotel")));
        assertEquals(1, evaluatorView(evaluator).size());
        assertNotNull(version.get("id"));
        assertEquals("INTENT_ROUTE", evaluator.get("evaluatorType"));
    }

    @Test
    void experimentRunsCasesThroughExecutorAndFinishesWithPassRate() {
        seedDatasetWithTwoCases();
        String evaluatorVersionId = seedIntentEvaluator();

        Map<String, Object> experiment = service.createExperiment(admin, Map.of(
                "code", "exp-1", "displayName", "首次回归", "datasetVersionId", datasetVersionId,
                "agentVersionId", "av-1", "evaluatorVersionIds", List.of(evaluatorVersionId)
        ));
        assertEquals("DRAFT", experiment.get("status"));
        Map<String, Object> started = service.startExperiment(admin, String.valueOf(experiment.get("id")));
        assertEquals("RUNNING", started.get("status"));

        service.processNext("worker-1");

        Map<String, Object> finished = service.experiment(admin, String.valueOf(experiment.get("id")));
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals(2, finished.get("completedCases"));
        assertEquals(2, finished.get("passedCases"));
        assertEquals(1.0, Double.parseDouble(String.valueOf(finished.get("passRate"))), 0.001);
    }

    @Test
    void restartsClaimedExperimentAfterLeaseExpiry() {
        seedDatasetWithTwoCases();
        String evaluatorVersionId = seedIntentEvaluator();
        Map<String, Object> experiment = service.createExperiment(admin, Map.of(
                "code", "exp-restart", "displayName", "重启恢复", "datasetVersionId", datasetVersionId,
                "agentVersionId", "av-1", "evaluatorVersionIds", List.of(evaluatorVersionId)
        ));
        service.startExperiment(admin, String.valueOf(experiment.get("id")));
        String experimentId = String.valueOf(experiment.get("id"));

        // Simulate a crashed worker holding an expired lease with no runs recorded.
        repository.saveExperiment(repository.experiment(experimentId).map(current -> new EvalExperiment(
                current.id(), current.code(), current.displayName(), current.datasetId(),
                current.datasetVersionId(), current.agentApplicationId(), current.agentVersionId(),
                current.evaluatorVersionIds(), "RUNNING", current.runKey(), current.totalCases(),
                current.completedCases(), current.passedCases(), current.failedCases(), current.errorCases(),
                current.costMicros(), current.thresholdPassRate(), null, "dead-worker",
                Instant.now().minusSeconds(300), current.startedAt(), null,
                current.createdBy(), current.createdAt(), Instant.now()
        )).orElseThrow());

        service.processNext("new-worker");

        Map<String, Object> finished = service.experiment(admin, experimentId);
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals(2, finished.get("completedCases"));
    }

    @Test
    void retryResumesPendingRunRowsWithoutDuplicatingThem() {
        seedDatasetWithTwoCases();
        String evaluatorVersionId = seedIntentEvaluator();
        Map<String, Object> experiment = service.createExperiment(admin, Map.of(
                "code", "exp-retry", "displayName", "重试复用", "datasetVersionId", datasetVersionId,
                "agentVersionId", "av-1", "evaluatorVersionIds", List.of(evaluatorVersionId)
        ));
        service.startExperiment(admin, String.valueOf(experiment.get("id")));
        String experimentId = String.valueOf(experiment.get("id"));

        // 模拟第一次运行崩溃后遗留的 PENDING 行；worker 必须复用而不是重复插入。
        for (EvalCase evalCase : repository.casesByDatasetVersion(datasetVersionId)) {
            repository.saveRun(new EvalExperimentRun(
                    "eru_pre_" + evalCase.id(), experimentId, evalCase.id(), evalCase.caseKey(), "PENDING",
                    null, null, null, List.of(), null, 0, 0L, null, null, Instant.now(), Instant.now()));
        }

        service.processNext("worker-1");

        Map<String, Object> finished = service.experiment(admin, experimentId);
        assertEquals("SUCCEEDED", finished.get("status"));
        assertEquals(2, finished.get("completedCases"));
        assertEquals(2, repository.runsByExperiment(experimentId).size(),
                "重试必须复用既有 run 行，不能新增重复行");
        assertTrue(repository.runsByExperiment(experimentId).stream()
                .allMatch(run -> "PASSED".equals(run.status())));
    }

    @Test
    void caseViewsMaskSensitiveValuesWhileRawDataStaysAtRest() {
        Map<String, Object> dataset = service.createDataset(admin, Map.of("code", "ds-mask", "displayName", "脱敏"));
        String versionId = String.valueOf(service.datasetVersions(admin, String.valueOf(dataset.get("id")))
                .getFirst().get("id"));
        service.addCase(admin, versionId, Map.of(
                "caseKey", "mask-1", "category", "manual",
                "input", Map.of("text", "我的手机 13812345678"),
                "expected", Map.of()
        ));
        Map<String, Object> view = service.cases(admin, versionId, null, null, 1, 10).items().getFirst();
        assertFalse(String.valueOf(view.get("input")).contains("13812345678"));
        EvalCase stored = repository.caseByKey(versionId, "mask-1").orElseThrow();
        assertTrue(String.valueOf(stored.input()).contains("13812345678"));
    }

    private void seedDatasetWithTwoCases() {
        service.createDataset(admin, Map.of("code", "ds1", "displayName", "数据集1"));
        datasetVersionId = String.valueOf(service.datasetVersions(admin,
                String.valueOf(service.datasets(admin, null, 1, 10).items().getFirst().get("id")))
                .getFirst().get("id"));
        service.addCase(admin, datasetVersionId, Map.of(
                "caseKey", "c1", "category", "intent-route",
                "input", Map.of("text", "查询酒店"), "expected", Map.of("agentCode", "hotel", "actionCode", "hotel.room.search")
        ));
        service.addCase(admin, datasetVersionId, Map.of(
                "caseKey", "c2", "category", "confirmation-gate",
                "input", Map.of("text", "取消订单"), "expected", Map.of("confirmationVersion", 1)
        ));
    }

    private String seedIntentEvaluator() {
        Map<String, Object> evaluator = service.createEvaluator(admin, Map.of(
                "code", "route-check", "displayName", "路由检查", "evaluatorType", "INTENT_ROUTE", "config", Map.of()
        ));
        Map<String, Object> version = service.createEvaluatorVersion(admin,
                String.valueOf(evaluator.get("id")), Map.of("config", Map.of()));
        return String.valueOf(version.get("id"));
    }

    private AgentEvaluationExecutor stubExecutor() {
        return execution -> new EvaluationRunOutcome(
                "已为您查询酒店房态。", List.of("message.final", "agent.route", "action.confirm"),
                "hotel", "hotel.room.search", "MODEL", "DOMAIN_AGENT",
                List.of("city", "date"), false, false, true, true, 1,
                List.of(), List.of(), List.of("city", "date"), 42, 420L, null
        );
    }

    @SuppressWarnings("unchecked")
    private List<Object> evaluatorView(Map<String, Object> evaluator) {
        return (List<Object>) evaluator.get("versions");
    }
}
