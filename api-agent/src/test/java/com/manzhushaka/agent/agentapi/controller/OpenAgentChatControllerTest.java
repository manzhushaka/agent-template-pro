package com.manzhushaka.agent.agentapi.controller;

import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.InMemoryAgentApplicationRepository;
import com.manzhushaka.agent.controlplane.InMemoryControlPlaneRepository;
import com.manzhushaka.agent.controlplane.InMemoryKnowledgeRepository;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import com.manzhushaka.agent.runtime.chat.AgentAppRuntimeContext;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the open Agent API against the real AgentApplicationService: API-key authentication,
 * immutable published version resolution, confirmation gate reuse and SSE streaming. The
 * orchestrator is faked only at its outer boundary, so the controller cannot bypass Runtime.
 */
class OpenAgentChatControllerTest {
    private final ControlPlaneService controlPlaneService = new ControlPlaneService();
    private final ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
    private final InMemoryControlPlaneRepository controlPlaneRepository = new InMemoryControlPlaneRepository();
    private final InMemoryAgentApplicationRepository applicationRepository = new InMemoryAgentApplicationRepository();
    private final AgentApplicationService applicationService = new AgentApplicationService(
            applicationRepository,
            controlPlaneRepository,
            new InMemoryKnowledgeRepository(),
            "model-demo");
    private final ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final TraceRecorder traceRecorder = mock(TraceRecorder.class);
    private final WebTestClient client = WebTestClient.bindToController(
            new OpenAgentChatController(applicationService, knowledgeBaseService, orchestrator, traceRecorder)).build();

    @Test
    void validKeyAndPublishedVersionReturnCompletion() {
        PublishedApp app = publishApp();
        when(orchestrator.message(anyString(), anyString(), anyString(), anyString(), any(AgentAppRuntimeContext.class)))
                .thenReturn(List.of(event("message.final", Map.of("content", "你好"))));

        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", app.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好")),
                        "conversationId", "conv-1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].message.content").isEqualTo("你好")
                .jsonPath("$.choices[0].finish_reason").isEqualTo("stop")
                .jsonPath("$.model").isEqualTo("model-demo")
                .jsonPath("$.keyId").isNotEmpty()
                .jsonPath("$.conversationId").isEqualTo("conv-1");
    }

    @Test
    void invalidRevokedAndExpiredKeysAreRejected() {
        PublishedApp app = publishApp();
        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", "ag_wrong_key")
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AGENT_API_KEY_INVALID");

        applicationService.revokeApiKey(admin, app.applicationId, app.keyId);
        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", app.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AGENT_API_KEY_REVOKED");

        String expiredPlaintext = "ag_expired_key_123";
        applicationRepository.saveApiKey(Map.of(
                "id", UUID.randomUUID().toString(),
                "applicationId", app.applicationId,
                "keyHash", sha256(expiredPlaintext),
                "keyPrefix", expiredPlaintext.substring(0, 12),
                "status", "ACTIVE",
                "scopes", List.of("chat:completions"),
                "expiresAt", Instant.now().minusSeconds(60).toString(),
                "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()),
                new com.manzhushaka.agent.controlplane.ControlPlaneAudit(
                        UUID.randomUUID().toString(), "admin", "AGENT_API_KEY_CREATED",
                        "AGENT_API_KEY", "k", Map.of(), Instant.now()));
        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", expiredPlaintext)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AGENT_API_KEY_EXPIRED");
    }

    @Test
    void eachApiKeyIsAnIsolatedRuntimeVisitor() {
        PublishedApp first = publishApp();
        when(orchestrator.createConversation(anyString()))
                .thenAnswer(invocation -> new com.manzhushaka.agent.runtime.chat.Conversation(
                        "conv-" + UUID.randomUUID(), invocation.getArgument(0),
                        "graph-" + UUID.randomUUID(), "api", Instant.now(), Instant.now()));
        when(orchestrator.message(anyString(), anyString(), anyString(), anyString(), any(AgentAppRuntimeContext.class)))
                .thenReturn(List.of(event("message.final", Map.of("content", "ok"))));
        Map<String, Object> secondKey = applicationService.createApiKey(admin, first.applicationId, null, List.of());

        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", first.code)
                .header("X-API-Key", first.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isOk();
        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", first.code)
                .header("X-API-Key", String.valueOf(secondKey.get("key")))
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isOk();

        verify(orchestrator, atLeastOnce()).message(
                eq("api-app-assistant-" + first.keyId),
                anyString(),
                anyString(),
                anyString(),
                any(AgentAppRuntimeContext.class));
        verify(orchestrator, atLeastOnce()).message(
                eq("api-app-assistant-" + secondKey.get("id")),
                anyString(),
                anyString(),
                anyString(),
                any(AgentAppRuntimeContext.class));
    }

    @Test
    void confirmationGateIsNotBypassedByOpenApi() {
        PublishedApp app = publishApp();
        when(orchestrator.message(anyString(), anyString(), anyString(), anyString(), any(AgentAppRuntimeContext.class)))
                .thenReturn(List.of(event("action.confirm", Map.of(
                        "actionCode", "order.create",
                        "confirmationVersion", "cv-1",
                        "title", "确认下单"))));

        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", app.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "帮我下单")),
                        "conversationId", "conv-1"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.choices[0].finish_reason").isEqualTo("requires_confirmation")
                .jsonPath("$.choices[0].message.content").isEqualTo("该请求需要二次确认后才能继续，未执行任何写操作。")
                .jsonPath("$.x-pending-confirmation.actionCode").isEqualTo("order.create");
    }

    @Test
    void streamRequestReturnsSseChunksWithDoneMarker() {
        PublishedApp app = publishApp();
        when(orchestrator.message(anyString(), anyString(), anyString(), anyString(), any(AgentAppRuntimeContext.class)))
                .thenReturn(List.of(event("message.final", Map.of("content", "流式回复"))));

        client.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", app.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好")),
                        "conversationId", "conv-2", "stream", true))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("[DONE]"));
                    org.junit.jupiter.api.Assertions.assertTrue(body.contains("chat.completion.chunk"));
                });
    }

    @Test
    void pinnedModelMustMatchConnectedModel() {
        AgentApplicationService otherService = new AgentApplicationService(
                applicationRepository,
                controlPlaneRepository,
                new InMemoryKnowledgeRepository(),
                "other-model");
        WebTestClient otherClient = WebTestClient.bindToController(
                new OpenAgentChatController(otherService, knowledgeBaseService, orchestrator, traceRecorder)).build();
        PublishedApp app = publishApp();

        otherClient.post().uri("/api/agent/v1/apps/{code}/chat/completions", app.code)
                .header("X-API-Key", app.key)
                .bodyValue(Map.of("messages", List.of(Map.of("role", "user", "content", "你好"))))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.error.code").isEqualTo("AGENT_MODEL_NOT_CONNECTED");
    }

    private PublishedApp publishApp() {
        controlPlaneRepository.saveDocument("MODEL", Map.of(
                "id", "model-1", "code", "model-demo", "displayName", "演示模型", "enabled", true));
        controlPlaneRepository.saveDocument("PROMPT", Map.of(
                "id", "prompt-1", "code", "greeting", "displayName", "问候", "publishedVersionId", "pv-1"));
        controlPlaneRepository.saveDocument("PROMPT_VERSION", Map.of(
                "id", "pv-1", "promptId", "prompt-1", "version", 1, "content", "你好"));
        Map<String, Object> app = applicationService.saveApplication(admin, null, Map.of(
                "code", "assistant", "displayName", "助手", "status", "ACTIVE"));
        String applicationId = String.valueOf(app.get("id"));
        Map<String, Object> version = applicationService.createVersion(admin, applicationId, Map.of(
                "modelCode", "model-demo",
                "promptId", "prompt-1",
                "promptVersionId", "pv-1",
                "bindings", List.of()));
        applicationService.publishVersion(admin, String.valueOf(version.get("id")));
        Map<String, Object> key = applicationService.createApiKey(admin, applicationId, null, List.of());
        return new PublishedApp(applicationId, "assistant",
                String.valueOf(key.get("id")), String.valueOf(key.get("key")));
    }

    private StreamEvent event(String type, Map<String, Object> payload) {
        return new StreamEvent(type, "conv-1", "req-1", 1, Instant.now(), payload);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record PublishedApp(String applicationId, String code, String keyId, String key) {
    }
}
