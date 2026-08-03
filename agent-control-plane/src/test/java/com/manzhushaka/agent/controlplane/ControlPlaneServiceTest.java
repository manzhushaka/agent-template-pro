package com.manzhushaka.agent.controlplane;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlaneServiceTest {
    @Test
    void hidesSecretReferenceAndEnforcesViewerPermissions() {
        ControlPlaneService service = new ControlPlaneService();
        ControlPlanePrincipal admin = service.principal("admin", "ADMIN");
        Map<String, Object> secret = service.saveSecretRef(admin, null, Map.of(
                "name", "openai-prod", "secretRefType", "ENV", "reference", "OPENAI_API_KEY", "configured", true
        ));

        assertFalse(secret.containsKey("reference"));
        assertThrows(ControlPlaneAccessDeniedException.class, () -> service.saveSecretRef(
                service.principal("viewer", "VIEWER"), null, Map.of("name", "forbidden")
        ));
    }

    @Test
    void publishesAndRollsBackImmutablePromptVersions() {
        ControlPlaneService service = new ControlPlaneService();
        ControlPlanePrincipal admin = service.principal("admin", "ADMIN");
        Map<String, Object> prompt = service.savePrompt(admin, null, Map.of(
                "code", "welcome", "displayName", "Welcome", "draftContent", "Hello {{name}}"
        ));
        Map<String, Object> first = service.createVersion(admin, (String) prompt.get("id"));
        service.publishPrompt(admin, (String) first.get("id"));
        service.savePrompt(admin, (String) prompt.get("id"), Map.of("draftContent", "Hi {{name}}"));
        Map<String, Object> second = service.createVersion(admin, (String) prompt.get("id"));
        service.publishPrompt(admin, (String) second.get("id"));
        service.rollbackPrompt(admin, (String) prompt.get("id"), (String) first.get("id"));

        assertEquals(first.get("id"), service.prompts(admin, "welcome").getFirst().get("publishedVersionId"));
        assertEquals("Hello Ada", service.debugPrompt(admin, (String) first.get("id"), Map.of("name", "Ada"))
                .get("renderedPrompt"));
        assertTrue(service.audits(admin).stream().anyMatch(audit ->
                "PROMPT_VERSION_PUBLISHED".equals(audit.action())
                        && second.get("id").equals(audit.resourceId())
                        && first.get("id").equals(audit.metadata().get("previousVersionId"))
        ));
        assertTrue(service.audits(admin).stream().anyMatch(audit ->
                "PROMPT_VERSION_ROLLED_BACK".equals(audit.action())
                        && first.get("id").equals(audit.resourceId())
                        && second.get("id").equals(audit.metadata().get("previousVersionId"))
        ));
    }

    @Test
    void reloadsControlPlaneResourcesAndAuditFromRepository() {
        InMemoryControlPlaneRepository repository = new InMemoryControlPlaneRepository();
        ControlPlaneService writer = new ControlPlaneService(repository);
        ControlPlanePrincipal admin = writer.principal("admin", "ADMIN");
        writer.savePrompt(admin, null, Map.of("code", "persisted", "displayName", "Persisted", "draftContent", "v1"));

        ControlPlaneService reloaded = new ControlPlaneService(repository);
        assertEquals(1, reloaded.prompts(reloaded.principal("admin", "ADMIN"), "persisted").size());
        assertEquals(1, reloaded.audits(reloaded.principal("admin", "ADMIN")).size());
    }

    @Test
    void unknownRoleFailsClosed() {
        ControlPlaneService service = new ControlPlaneService();
        ControlPlanePrincipal principal = service.principal("unexpected", "SUPER_ADMIN");

        assertTrue(principal.permissions().isEmpty());
        assertThrows(ControlPlaneAccessDeniedException.class, () -> service.models(principal, null, null));
        assertThrows(ControlPlaneAccessDeniedException.class, () -> service.require(principal, ControlPlaneService.RUNTIME_READ));
    }

    @Test
    void derivesSecretStatusAndNeverReturnsLocatorOrBindingId() {
        ControlPlaneService service = new ControlPlaneService(
                new InMemoryControlPlaneRepository(),
                (type, locator) -> "ENV".equals(type) && "PROVIDER_API_KEY".equals(locator)
                        ? java.util.Optional.of("resolved-only-at-use-time")
                        : java.util.Optional.empty(),
                (endpoint, credential, timeout) -> new ProviderConnectionTestResult("CONNECTED", "connected"),
                Set.of("example.com"),
                java.time.Duration.ofSeconds(1)
        );
        ControlPlanePrincipal admin = service.principal("admin", "ADMIN");
        Map<String, Object> secret = service.saveSecretRef(admin, null, Map.of(
                "name", "provider", "secretRefType", "ENV", "reference", "PROVIDER_API_KEY", "configured", false
        ));
        Map<String, Object> model = service.saveModel(admin, null, Map.of(
                "code", "chat", "modelType", "CHAT", "modelName", "chat-1",
                "baseUrl", "https://example.com/v1", "secretRefId", secret.get("id")
        ));

        assertEquals(true, secret.get("configured"));
        assertFalse(secret.containsKey("reference"));
        assertFalse(model.containsKey("secretRefId"));
        assertFalse(service.models(admin, null, null).getFirst().containsKey("secretRefId"));
        assertEquals("CONNECTED", service.testModel(admin, (String) model.get("id")).get("status"));
        assertTrue(service.audits(admin).stream().noneMatch(audit ->
                audit.metadata().toString().contains("PROVIDER_API_KEY")
                        || audit.metadata().toString().contains("resolved-only-at-use-time")
        ));
    }

    @Test
    void rejectsMissingSecretReferenceAndPrivateOrUnlistedEndpoints() {
        ControlPlaneService service = new ControlPlaneService(
                new InMemoryControlPlaneRepository(),
                SecretRefResolver.unavailable(),
                ProviderConnectionTester.unavailable(),
                Set.of("api.example.com"),
                java.time.Duration.ofSeconds(1)
        );
        ControlPlanePrincipal admin = service.principal("admin", "ADMIN");

        assertThrows(IllegalArgumentException.class, () -> service.saveModel(admin, null, Map.of(
                "code", "bad-secret", "secretRefId", "missing"
        )));
        assertThrows(IllegalArgumentException.class, () -> service.saveModel(admin, null, Map.of(
                "code", "ssrf", "baseUrl", "https://127.0.0.1/v1"
        )));
    }

    @Test
    void recordsRollbackDistinctlyAndAllocatesConcurrentVersionsWithoutDuplicates() throws Exception {
        ControlPlaneService service = new ControlPlaneService();
        ControlPlanePrincipal admin = service.principal("admin", "ADMIN");
        Map<String, Object> prompt = service.savePrompt(admin, null, Map.of(
                "code", "concurrent", "draftContent", "content"
        ));
        String promptId = (String) prompt.get("id");
        Map<String, Object> first = service.createVersion(admin, promptId);
        service.publishPrompt(admin, (String) first.get("id"));

        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = IntStream.range(0, 20)
                    .mapToObj(ignored -> executor.submit(() -> service.createVersion(admin, promptId)))
                    .toList();
            for (var future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
        service.rollbackPrompt(admin, promptId, (String) first.get("id"));

        List<Integer> versions = service.promptVersions(admin, promptId).stream()
                .map(item -> ((Number) item.get("version")).intValue())
                .toList();
        assertEquals(21, Set.copyOf(versions).size());
        assertTrue(service.audits(admin).stream().anyMatch(audit ->
                "PROMPT_VERSION_ROLLED_BACK".equals(audit.action())
                        && first.get("id").equals(audit.metadata().get("targetVersionId"))
        ));
    }
}
