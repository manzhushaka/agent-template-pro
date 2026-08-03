package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.InMemoryAgentApplicationRepository;
import com.manzhushaka.agent.controlplane.InMemoryControlPlaneRepository;
import com.manzhushaka.agent.controlplane.InMemoryKnowledgeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentApplicationControllerTest {
    private final ControlPlaneService controlPlaneService = new ControlPlaneService();
    private final ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
    private final InMemoryControlPlaneRepository controlPlaneRepository = new InMemoryControlPlaneRepository();
    private final AgentApplicationService applicationService = new AgentApplicationService(
            new InMemoryAgentApplicationRepository(),
            controlPlaneRepository,
            new InMemoryKnowledgeRepository(),
            "model-demo");
    private final ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
    private final WebTestClient client = WebTestClient.bindToController(
            new AgentApplicationController(authentication, applicationService)).build();

    @Test
    void unauthenticatedRequestReturns401() {
        when(authentication.requirePrincipal(any())).thenThrow(
                new ConsoleAuthenticationException(ConsoleAuthenticationException.Reason.SESSION_INVALID));

        client.get().uri("/api/console/v1/applications")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_SESSION_INVALID");
    }

    @Test
    void viewerCannotCreateApplication() {
        when(authentication.requirePrincipal(any())).thenReturn(
                controlPlaneService.principal("viewer", "VIEWER"));

        client.post().uri("/api/console/v1/applications")
                .header("Authorization", "Bearer viewer")
                .bodyValue(Map.of("code", "app-1", "displayName", "应用"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_PERMISSION_DENIED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void createPublishRollbackApiKeyAndOpenApiFlow() {
        when(authentication.requirePrincipal(any())).thenReturn(admin);
        seedResources();
        String applicationId = createApplication("customer-assistant", "DRAFT");

        Map<String, Object> version = createVersion(applicationId);
        String versionId = String.valueOf(version.get("id"));

        client.post().uri("/api/console/v1/application-versions/{versionId}:publish", versionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("PUBLISHED");

        client.post().uri("/api/console/v1/application-versions/{versionId}:publish", versionId)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AGENT_APP_STATE_CONFLICT");

        Map<String, Object> key = client.post().uri("/api/console/v1/applications/{id}/api-keys", applicationId)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertTrue(String.valueOf(key.get("key")).startsWith("ag_"));
        assertFalse(key.containsKey("keyHash"));

        client.get().uri("/api/console/v1/applications/{id}/api-keys", applicationId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].keyPrefix").value(prefix -> ((String) prefix).startsWith("ag_"))
                .jsonPath("$[0].keyHash").doesNotExist()
                .jsonPath("$[0].key").doesNotExist();

        client.get().uri("/api/console/v1/applications/{id}:openapi", applicationId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").isEqualTo("3.0.3")
                .jsonPath("$.paths['/apps/{appCode}/chat/completions'].post.operationId").isEqualTo("chatCompletions")
                .jsonPath("$.components.securitySchemes.ApiKeyAuth.name").isEqualTo("X-API-Key");

        client.get().uri("/api/console/v1/applications/{id}/versions", applicationId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1);

        client.get().uri("/api/console/v1/applications/{id}/publish-records", applicationId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].action").isEqualTo("PUBLISH");
    }

    @Test
    @SuppressWarnings("unchecked")
    void unvalidatedVersionCannotBePublishedAndPaginationFilters() {
        when(authentication.requirePrincipal(any())).thenReturn(admin);
        String applicationId = createApplication("broken-app", "DRAFT");
        Map<String, Object> version = client.post().uri("/api/console/v1/applications/{id}/versions", applicationId)
                .bodyValue(Map.of(
                        "modelCode", "missing-model",
                        "promptId", "prompt-missing",
                        "promptVersionId", "pv-missing",
                        "bindings", List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();

        client.post().uri("/api/console/v1/application-versions/{versionId}:publish", version.get("id"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("AGENT_APP_STATE_CONFLICT");

        createApplication("alpha", "ACTIVE");
        createApplication("beta", "ACTIVE");
        client.get().uri("/api/console/v1/applications?keyword=alpha&page=1&size=1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].code").isEqualTo("alpha");

        client.get().uri("/api/console/v1/applications?page=1&size=2")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(3)
                .jsonPath("$.items.length()").isEqualTo(2);
    }

    private void seedResources() {
        controlPlaneRepository.saveDocument("MODEL", Map.of(
                "id", "model-1", "code", "model-demo", "displayName", "演示模型", "enabled", true));
        controlPlaneRepository.saveDocument("PROMPT", Map.of(
                "id", "prompt-1", "code", "greeting", "displayName", "问候", "publishedVersionId", "pv-1"));
        controlPlaneRepository.saveDocument("PROMPT_VERSION", Map.of(
                "id", "pv-1", "promptId", "prompt-1", "version", 1, "content", "你好"));
    }

    private String createApplication(String code, String status) {
        Map<String, Object> body = client.post().uri("/api/console/v1/applications")
                .bodyValue(Map.of("code", code, "displayName", code, "status", status))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        return String.valueOf(body.get("id"));
    }

    private Map<String, Object> createVersion(String applicationId) {
        return client.post().uri("/api/console/v1/applications/{id}/versions", applicationId)
                .bodyValue(Map.of(
                        "modelCode", "model-demo",
                        "promptId", "prompt-1",
                        "promptVersionId", "pv-1",
                        "bindings", List.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
    }
}
