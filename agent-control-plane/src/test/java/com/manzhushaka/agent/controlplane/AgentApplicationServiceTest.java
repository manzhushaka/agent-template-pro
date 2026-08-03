package com.manzhushaka.agent.controlplane;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApplicationServiceTest {
    private final InMemoryAgentApplicationRepository repository = new InMemoryAgentApplicationRepository();
    private final InMemoryControlPlaneRepository controlPlaneRepository = new InMemoryControlPlaneRepository();
    private final InMemoryKnowledgeRepository knowledgeRepository = new InMemoryKnowledgeRepository();
    private final ControlPlaneService controlPlaneService = new ControlPlaneService();
    private final AgentApplicationService service =
            new AgentApplicationService(repository, controlPlaneRepository, knowledgeRepository, "model-demo");
    private final ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");

    @Test
    void publishesImmutableVersionAndRollsBackWithPublishRecord() {
        seedResources();
        Map<String, Object> app = service.saveApplication(admin, null, Map.of(
                "code", "customer-assistant", "displayName", "客户助手", "status", "DRAFT"));
        String applicationId = String.valueOf(app.get("id"));

        Map<String, Object> version = service.createVersion(admin, applicationId, Map.of(
                "modelCode", "model-demo",
                "promptId", "prompt-1",
                "promptVersionId", "pv-2",
                "knowledgeBaseId", "kb-1",
                "bindings", List.of()));
        String versionId = String.valueOf(version.get("id"));
        assertTrue(Boolean.TRUE.equals(service.validateVersion(admin, versionId).get("valid")));
        assertEquals("DRAFT", version.get("status"));

        service.publishVersion(admin, versionId);
        Map<String, Object> published = service.version(admin, versionId);
        assertEquals("PUBLISHED", published.get("status"));
        // 发布版本即应用上线点：应用必须进入 ACTIVE 才能被 OpenAPI / 评估执行器解析。
        assertEquals("ACTIVE", service.applications(admin, "customer-assistant").getFirst().get("status"));
        assertEquals("customer-assistant", service.resolvePublishedAppForCall("customer-assistant").appCode());
        assertNotNull(published.get("publishedAt"));
        assertThrows(IllegalStateException.class, () -> service.publishVersion(admin, versionId));

        Map<String, Object> second = service.createVersion(admin, applicationId, Map.of(
                "modelCode", "model-demo",
                "promptId", "prompt-1",
                "promptVersionId", "pv-2"));
        service.publishVersion(admin, String.valueOf(second.get("id")));

        Map<String, Object> rolledBack = service.rollbackApplication(admin, applicationId, versionId);
        assertEquals(versionId, rolledBack.get("id"));
        List<Map<String, Object>> records = service.publishRecords(admin, applicationId);
        assertEquals(3, records.size());
        assertEquals("ROLLBACK", records.getFirst().get("action"));
    }

    @Test
    void validationRejectsUnpublishedPromptAndMissingModel() {
        controlPlaneRepository.saveDocument("MODEL", Map.of(
                "id", "model-1", "code", "model-demo", "displayName", "演示模型", "enabled", true));
        controlPlaneRepository.saveDocument("PROMPT", Map.of(
                "id", "prompt-1", "code", "greeting", "displayName", "问候", "publishedVersionId", "pv-1"));
        controlPlaneRepository.saveDocument("PROMPT_VERSION", Map.of(
                "id", "pv-1", "promptId", "prompt-1", "version", 1, "content", "你好"));
        Map<String, Object> app = service.saveApplication(admin, null, Map.of(
                "code", "broken-app", "displayName", "破损应用"));
        String applicationId = String.valueOf(app.get("id"));

        Map<String, Object> version = service.createVersion(admin, applicationId, Map.of(
                "modelCode", "missing-model",
                "promptId", "prompt-1",
                "promptVersionId", "pv-2"));
        Map<String, Object> validation = service.validateVersion(admin, String.valueOf(version.get("id")));
        assertFalse(Boolean.TRUE.equals(validation.get("valid")));
        assertTrue(validation.get("issues") instanceof List<?> issues && !issues.isEmpty());
        assertThrows(IllegalStateException.class, () -> service.publishVersion(admin, String.valueOf(version.get("id"))));
    }

    @Test
    void apiKeyIsHashOnlyAndRotateRevokeAndExpiryAreEnforced() {
        seedResources();
        Map<String, Object> app = service.saveApplication(admin, null, Map.of(
                "code", "key-app", "displayName", "密钥应用"));
        String applicationId = String.valueOf(app.get("id"));

        Map<String, Object> created = service.createApiKey(admin, applicationId, null, List.of());
        String plaintext = String.valueOf(created.get("key"));
        assertTrue(plaintext.startsWith("ag_"));
        assertFalse(created.containsKey("keyHash"));
        assertTrue(created.containsKey("key"));
        assertEquals("ACTIVE", created.get("status"));

        List<Map<String, Object>> listed = service.apiKeys(admin, applicationId);
        assertFalse(listed.getFirst().containsKey("keyHash"));
        assertEquals("ag_", String.valueOf(listed.getFirst().get("keyPrefix")).substring(0, 3));

        String keyId = String.valueOf(created.get("id"));
        assertEquals(keyId, service.authenticateApiKey("key-app", plaintext));
        assertThrows(IllegalArgumentException.class, () -> service.authenticateApiKey("key-app", plaintext + "x"));

        Map<String, Object> rotated = service.rotateApiKey(admin, applicationId, keyId);
        String rotatedPlaintext = String.valueOf(rotated.get("key"));
        assertThrows(IllegalArgumentException.class, () -> service.authenticateApiKey("key-app", plaintext));
        assertEquals(String.valueOf(rotated.get("id")), service.authenticateApiKey("key-app", rotatedPlaintext));
        assertThrows(IllegalStateException.class, () -> service.archiveApplication(admin, applicationId));

        service.revokeApiKey(admin, applicationId, String.valueOf(rotated.get("id")));
        assertThrows(IllegalArgumentException.class, () -> service.authenticateApiKey("key-app", rotatedPlaintext));
        service.archiveApplication(admin, applicationId);

        String expiredId = "expired-key";
        repository.saveApiKey(Map.of(
                "id", expiredId,
                "applicationId", applicationId,
                "keyHash", sha256("ag_expired"),
                "keyPrefix", "ag_expired",
                "status", "ACTIVE",
                "scopes", List.of("chat:completions"),
                "expiresAt", Instant.parse("2020-01-01T00:00:00Z").toString(),
                "createdAt", Instant.parse("2019-01-01T00:00:00Z").toString(),
                "updatedAt", Instant.parse("2019-01-01T00:00:00Z").toString()), null);
        assertThrows(IllegalArgumentException.class, () -> service.authenticateApiKey("key-app", "ag_expired"));
    }

    @Test
    void openApiSpecExposesOnlyControlledEndpointAndNoSecrets() {
        seedResources();
        Map<String, Object> app = service.saveApplication(admin, null, Map.of(
                "code", "spec-app", "displayName", "规范应用"));
        String applicationId = String.valueOf(app.get("id"));
        Map<String, Object> version = service.createVersion(admin, applicationId, Map.of(
                "modelCode", "model-demo",
                "promptId", "prompt-1",
                "promptVersionId", "pv-2"));
        service.publishVersion(admin, String.valueOf(version.get("id")));

        Map<String, Object> spec = service.openApiSpec(admin, applicationId);
        assertTrue(spec.containsKey("openapi"));
        assertTrue(String.valueOf(spec.get("paths")).contains("/apps/{appCode}/chat/completions"));
        String serialized = spec.toString();
        assertFalse(serialized.contains("keyHash"));
        assertFalse(serialized.contains("你好，请回答"));
        assertTrue(serialized.contains("model-demo"));
        assertEquals("chat:completions", AgentApplicationService.DEFAULT_SCOPE);
    }

    @Test
    void rbacBlocksViewerFromWriteAndApiKeyOperations() {
        ControlPlanePrincipal viewer = controlPlaneService.principal("viewer", "VIEWER");
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.saveApplication(viewer, null, Map.of("code", "x", "displayName", "x")));
        seedResources();
        Map<String, Object> app = service.saveApplication(admin, null, Map.of(
                "code", "rbac-app", "displayName", "权限应用"));
        assertThrows(ControlPlaneAccessDeniedException.class,
                () -> service.createApiKey(viewer, String.valueOf(app.get("id")), null, List.of()));
    }

    private void seedResources() {
        controlPlaneRepository.saveDocument("MODEL", Map.of(
                "id", "model-1", "code", "model-demo", "displayName", "演示模型", "enabled", true));
        controlPlaneRepository.saveDocument("PROMPT", Map.of(
                "id", "prompt-1", "code", "greeting", "displayName", "问候", "publishedVersionId", "pv-2"));
        controlPlaneRepository.saveDocument("PROMPT_VERSION", Map.of(
                "id", "pv-1", "promptId", "prompt-1", "version", 1, "content", "第一版"));
        controlPlaneRepository.saveDocument("PROMPT_VERSION", Map.of(
                "id", "pv-2", "promptId", "prompt-1", "version", 2, "content", "你好，请回答"));
        knowledgeRepository.saveKnowledgeBase(Map.of(
                "id", "kb-1", "code", "handbook", "displayName", "手册", "status", "ACTIVE",
                "createdAt", Instant.now().toString(), "updatedAt", Instant.now().toString()), null);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
