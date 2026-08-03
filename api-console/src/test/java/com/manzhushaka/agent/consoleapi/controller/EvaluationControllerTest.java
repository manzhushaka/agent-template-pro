package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.evaluation.AgentEvaluationExecutor;
import com.manzhushaka.agent.controlplane.evaluation.EvaluationRunOutcome;
import com.manzhushaka.agent.controlplane.evaluation.EvaluationService;
import com.manzhushaka.agent.controlplane.evaluation.InMemoryEvaluationRepository;
import com.manzhushaka.agent.controlplane.evaluation.plugin.IntentRouteEvaluator;
import com.manzhushaka.agent.runtime.trace.TraceQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationControllerTest {
    @Test
    void runsDatasetCaseAndExperimentLifecycleThroughRealApi() {
        ControlPlaneService controlPlaneService = new ControlPlaneService();
        ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
        EvaluationService service = service(admin, controlPlaneService);
        WebTestClient client = client(admin, service);

        Map<?, ?> dataset = client.post().uri("/api/console/v1/evaluation/datasets")
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("code", "hotel-eval", "displayName", "酒店评估"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String datasetId = String.valueOf(dataset.get("id"));
        String versionId = String.valueOf(dataset.get("currentVersionId"));

        client.post().uri("/api/console/v1/evaluation/datasets/versions/{versionId}/cases", versionId)
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of(
                        "caseKey", "c1", "category", "intent-route",
                        "input", Map.of("text", "查询上海酒店"),
                        "expected", Map.of("agentCode", "hotel", "actionCode", "hotel.room.search")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.caseKey").isEqualTo("c1");
        client.post().uri("/api/console/v1/evaluation/datasets/versions/{versionId}/cases", versionId)
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of(
                        "caseKey", "c2", "category", "confirmation-gate",
                        "input", Map.of("text", "取消订单"),
                        "expected", Map.of("confirmationVersion", 1)))
                .exchange()
                .expectStatus().isOk();

        Map<?, ?> evaluator = client.post().uri("/api/console/v1/evaluation/evaluators")
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of(
                        "code", "route-check", "displayName", "路由检查",
                        "evaluatorType", "INTENT_ROUTE", "config", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String evaluatorId = String.valueOf(evaluator.get("id"));
        Map<?, ?> evaluatorVersion = client.post()
                .uri("/api/console/v1/evaluation/evaluators/{id}/versions", evaluatorId)
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("config", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String evaluatorVersionId = String.valueOf(evaluatorVersion.get("id"));

        Map<?, ?> experiment = client.post().uri("/api/console/v1/evaluation/experiments")
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of(
                        "code", "exp-1", "displayName", "首次回归",
                        "datasetVersionId", versionId, "agentVersionId", "av-1",
                        "evaluatorVersionIds", List.of(evaluatorVersionId)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
        String experimentId = String.valueOf(experiment.get("id"));
        assertEquals("DRAFT", experiment.get("status"));

        client.post().uri("/api/console/v1/evaluation/experiments/{id}:start", experimentId)
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING");

        service.processNext("test-worker");

        client.get().uri("/api/console/v1/evaluation/experiments/{id}/summary", experimentId)
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("SUCCEEDED")
                .jsonPath("$.passRate").isEqualTo(1.0)
                .jsonPath("$.completedCases").isEqualTo(2);

        client.get().uri(uriBuilder -> uriBuilder.path("/api/console/v1/evaluation/experiments/{id}/results")
                        .queryParam("page", 1).queryParam("size", 10).build(experimentId))
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(2)
                .jsonPath("$.items[0].status").exists();

        client.get().uri(uriBuilder -> uriBuilder.path("/api/console/v1/evaluation/datasets")
                        .queryParam("query", "hotel").build())
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(datasetId);
    }

    @Test
    void viewerCannotWriteDatasets() {
        ControlPlaneService controlPlaneService = new ControlPlaneService();
        ControlPlanePrincipal viewer = controlPlaneService.principal("viewer", "VIEWER");
        EvaluationService service = service(viewer, controlPlaneService);
        WebTestClient client = client(viewer, service);

        client.post().uri("/api/console/v1/evaluation/datasets")
                .header("Authorization", "Bearer viewer")
                .bodyValue(Map.of("code", "blocked", "displayName", "不允许"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_PERMISSION_DENIED");
    }

    @Test
    void expiredSessionMapsToUnauthorized() {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePermission(any(), anyString())).thenThrow(
                new ConsoleAuthenticationException(ConsoleAuthenticationException.Reason.SESSION_INVALID));
        EvaluationService service = mock(EvaluationService.class);
        WebTestClient client = WebTestClient.bindToController(
                        new EvaluationController(authentication, service, mock(TraceQueryPort.class)))
                .controllerAdvice(new ConsoleApiExceptionAdvice())
                .build();

        client.get().uri("/api/console/v1/evaluation/datasets")
                .header("Authorization", "Bearer expired")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_SESSION_INVALID");
    }

    private static EvaluationService service(
            ControlPlanePrincipal principal,
            ControlPlaneService controlPlaneService
    ) {
        AgentApplicationService agentApplicationService = mock(AgentApplicationService.class);
        when(agentApplicationService.version(any(), any())).thenReturn(Map.of("id", "av-1", "status", "PUBLISHED"));
        when(agentApplicationService.appCodeForVersion("av-1")).thenReturn("customer-assistant");
        return new EvaluationService(
                new InMemoryEvaluationRepository(),
                agentApplicationService,
                List.of(new IntentRouteEvaluator()),
                (AgentEvaluationExecutor) execution -> new EvaluationRunOutcome(
                        "已为您查询酒店房态。", List.of("message.final", "agent.route", "action.confirm"),
                        "hotel", "hotel.room.search", "MODEL", "DOMAIN_AGENT",
                        List.of("city", "date"), false, false, true, true, 1,
                        List.of(), List.of(), List.of("city", "date"), 42, 420L, null)
        );
    }

    private static WebTestClient client(ControlPlanePrincipal principal, EvaluationService service) {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePermission(any(), anyString())).thenReturn(principal);
        return WebTestClient.bindToController(
                        new EvaluationController(authentication, service, mock(TraceQueryPort.class)))
                .controllerAdvice(new ConsoleApiExceptionAdvice())
                .build();
    }
}
