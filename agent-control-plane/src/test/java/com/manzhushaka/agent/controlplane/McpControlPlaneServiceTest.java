package com.manzhushaka.agent.controlplane;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpControlPlaneServiceTest {
    @Test
    void rejectsSsrfAndNeverReturnsSecretBinding() {
        Fixture fixture = fixture(List.of());

        assertThrows(IllegalArgumentException.class, () -> fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "loopback", "transport", "STREAMABLE_HTTP", "endpoint", "https://127.0.0.1/mcp"
        )));
        assertThrows(IllegalArgumentException.class, () -> fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "token-in-url", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/mcp?token=secret"
        )));
        assertThrows(IllegalArgumentException.class, () -> fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "userinfo", "transport", "STREAMABLE_HTTP", "endpoint", "https://user:pass@mcp.example.com/mcp"
        )));
        assertThrows(IllegalArgumentException.class, () -> fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "suffix-trick", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com.attacker.invalid/mcp"
        )));
        Map<String, Object> secret = fixture.control.saveSecretRef(fixture.admin, null, Map.of(
                "name", "mcp-key", "secretRefType", "ENV", "reference", "MCP_API_KEY"
        ));
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "catalog", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "secretRefId", secret.get("id"), "enabled", true
        ));

        assertFalse(server.containsKey("secretRefId"));
        assertFalse(server.toString().contains("mcp-resolved-token"));
        assertTrue((Boolean) server.get("secretConfigured"));
    }

    @Test
    void snapshotsSchemaAndKeepsExistingAgentBindingPinned() {
        AtomicReference<List<McpDiscoveredTool>> tools = new AtomicReference<>(List.of(readTool("string")));
        Fixture fixture = fixture(tools);
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "catalog", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        Map<String, Object> tool = fixture.mcp.tools(fixture.admin, null, null).getFirst();
        String firstVersionId = (String) tool.get("latestVersionId");
        Map<String, Object> binding = fixture.mcp.bindAgent(fixture.admin, null, Map.of("agentCode", "travel", "toolVersionId", firstVersionId));

        tools.set(List.of(readTool("integer")));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));

        assertEquals(2, fixture.mcp.toolVersions(fixture.admin, (String) tool.get("id")).size());
        assertEquals(firstVersionId, binding.get("toolVersionId"));
        assertTrue(fixture.control.audits(fixture.admin).stream().anyMatch(audit -> "MCP_TOOL_VERSION_DISCOVERED".equals(audit.action())));

        tools.set(List.of());
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        assertTrue(fixture.control.audits(fixture.admin).stream().anyMatch(audit -> "MCP_TOOL_RETIRED".equals(audit.action())));

        tools.set(List.of(readTool("integer")));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        Map<String, Object> rediscovered = fixture.mcp.tools(fixture.admin, null, null).getFirst();
        assertFalse(rediscovered.containsKey("retiredAt"));
    }

    @Test
    void snapshotsRiskChangesAndCanonicalizesMapOrdering() {
        AtomicReference<List<McpDiscoveredTool>> tools = new AtomicReference<>(List.of(new McpDiscoveredTool(
                "orders.lookup", "lookup", Map.of("required", List.of("id"), "type", "object"), Map.of(), "LOW", false
        )));
        Fixture fixture = fixture(tools);
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "orders", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        String serverId = (String) server.get("id");
        fixture.mcp.syncServer(fixture.admin, serverId);
        String toolId = (String) fixture.mcp.tools(fixture.admin, null, null).getFirst().get("id");

        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("type", "object");
        reordered.put("required", List.of("id"));
        tools.set(List.of(new McpDiscoveredTool("orders.lookup", "lookup", reordered, Map.of(), "LOW", false)));
        fixture.mcp.syncServer(fixture.admin, serverId);
        assertEquals(1, fixture.mcp.toolVersions(fixture.admin, toolId).size());

        tools.set(List.of(new McpDiscoveredTool("orders.lookup", "lookup", reordered, Map.of(), "HIGH", true)));
        fixture.mcp.syncServer(fixture.admin, serverId);
        assertEquals(2, fixture.mcp.toolVersions(fixture.admin, toolId).size());
        assertTrue((Boolean) fixture.mcp.tools(fixture.admin, null, null).getFirst().get("writeTool"));
    }

    @Test
    void preservesJsonNullInSchemaSnapshotsAndAuditsDiscoveryFailure() {
        Map<String, Object> property = new java.util.LinkedHashMap<>();
        property.put("type", "string");
        property.put("default", null);
        AtomicReference<List<McpDiscoveredTool>> tools = new AtomicReference<>(List.of(new McpDiscoveredTool(
                "orders.lookup", "lookup", Map.of("type", "object", "properties", Map.of("id", property)), Map.of(), "LOW", false
        )));
        Fixture fixture = fixture(tools);
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "orders", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        String toolId = (String) fixture.mcp.tools(fixture.admin, null, null).getFirst().get("id");
        assertEquals(1, fixture.mcp.toolVersions(fixture.admin, toolId).size());

        tools.set(List.of(
                readTool("string"),
                readTool("integer")
        ));
        assertThrows(McpTransportException.class, () -> fixture.mcp.syncServer(fixture.admin, (String) server.get("id")));
        assertTrue(fixture.control.audits(fixture.admin).stream()
                .anyMatch(audit -> "MCP_SERVER_SYNC_FAILED".equals(audit.action())));
    }

    @Test
    void deniesViewerWritesAndBlocksWriteDebugAndReferencedDisable() {
        Fixture fixture = fixture(List.of(new McpDiscoveredTool("delete.order", "delete", Map.of(), Map.of(), "HIGH", true)));
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "orders", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        Map<String, Object> tool = fixture.mcp.tools(fixture.admin, null, null).getFirst();
        fixture.mcp.bindAgent(fixture.admin, null, Map.of("agentCode", "orders", "toolVersionId", tool.get("latestVersionId")));

        assertThrows(ControlPlaneAccessDeniedException.class, () -> fixture.mcp.saveServer(fixture.viewer, null, Map.of("code", "forbidden")));
        assertThrows(McpWriteToolConfirmationRequiredException.class, () -> fixture.mcp.debugTool(fixture.admin, (String) tool.get("id"), Map.of()));
        assertThrows(McpToolReferenceConflictException.class, () -> fixture.mcp.setToolEnabled(fixture.admin, (String) tool.get("id"), false));
        assertThrows(McpBindingConflictException.class, () -> fixture.mcp.bindAgent(fixture.admin, null,
                Map.of("agentCode", "orders", "toolVersionId", tool.get("latestVersionId"))));
        assertThrows(McpBindingConflictException.class, () -> fixture.mcp.saveServer(fixture.admin, (String) server.get("id"),
                Map.of("enabled", false)));
        assertTrue(fixture.control.audits(fixture.admin).stream().anyMatch(audit -> "MCP_TOOL_DEBUG_DENIED".equals(audit.action())));
    }

    @Test
    void blocksBindingAndDebugForRetiredTools() {
        AtomicReference<List<McpDiscoveredTool>> tools = new AtomicReference<>(List.of(readTool("string")));
        Fixture fixture = fixture(tools);
        Map<String, Object> server = fixture.mcp.saveServer(fixture.admin, null, Map.of(
                "code", "orders", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));
        Map<String, Object> tool = fixture.mcp.tools(fixture.admin, null, null).getFirst();
        tools.set(List.of());
        fixture.mcp.syncServer(fixture.admin, (String) server.get("id"));

        assertThrows(IllegalStateException.class, () -> fixture.mcp.bindAgent(fixture.admin, null,
                Map.of("agentCode", "orders", "toolVersionId", tool.get("latestVersionId"))));
        assertThrows(IllegalStateException.class, () -> fixture.mcp.debugTool(fixture.admin, (String) tool.get("id"), Map.of()));
        assertThrows(IllegalStateException.class, () -> fixture.mcp.setToolEnabled(fixture.admin, (String) tool.get("id"), true));
    }

    @Test
    void serializesConcurrentSyncAndBinding() throws Exception {
        CountDownLatch discoveryStarted = new CountDownLatch(1);
        CountDownLatch releaseDiscovery = new CountDownLatch(1);
        InMemoryControlPlaneRepository repository = new InMemoryControlPlaneRepository();
        ControlPlaneService control = new ControlPlaneService(repository);
        ControlPlanePrincipal admin = control.principal("admin", "ADMIN");
        McpTransportClient transport = new McpTransportClient() {
            @Override public McpTransportResult test(McpServerConnection server, Duration timeout) { return new McpTransportResult("CONNECTED", "connected"); }
            @Override public List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout) {
                discoveryStarted.countDown();
                try { releaseDiscovery.await(5, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return List.of(readTool("string"));
            }
            @Override
            public McpTransportCallResult call(
                    McpServerConnection server,
                    String toolName,
                    Map<String, Object> arguments,
                    Duration timeout
            ) {
                return new McpTransportCallResult(McpTransportCallResult.OK, Map.of("ok", true), null);
            }
        };
        McpControlPlaneService service = new McpControlPlaneService(control, repository, SecretRefResolver.unavailable(), transport, Set.of("mcp.example.com"), Set.of(), false, Duration.ofSeconds(1));
        String serverId = (String) service.saveServer(admin, null, Map.of("code", "concurrent", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true)).get("id");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.syncServer(admin, serverId));
            assertTrue(discoveryStarted.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(McpSyncConflictException.class, () -> service.syncServer(admin, serverId)));
            releaseDiscovery.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        Map<String, Object> tool = service.tools(admin, null, null).getFirst();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> bindOnce(service, admin, tool));
            var second = executor.submit(() -> bindOnce(service, admin, tool));
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, service.references(admin, (String) tool.get("id")).size());
    }

    @Test
    void reclaimsExpiredSyncLeaseWithoutReleasingNewOwner() {
        InMemoryControlPlaneRepository repository = new InMemoryControlPlaneRepository();
        Instant startedAt = Instant.parse("2026-08-03T00:00:00Z");

        assertTrue(repository.claimMcpSync("sync-1", "server-1", startedAt, startedAt.plusSeconds(30)));
        assertFalse(repository.claimMcpSync("sync-2", "server-1", startedAt.plusSeconds(10), startedAt.plusSeconds(40)));
        assertTrue(repository.claimMcpSync("sync-2", "server-1", startedAt.plusSeconds(30), startedAt.plusSeconds(60)));

        repository.finishMcpSync("sync-1", "FAILED", 0, 0, "LATE_FINISH", startedAt.plusSeconds(31));
        assertFalse(repository.claimMcpSync("sync-3", "server-1", startedAt.plusSeconds(40), startedAt.plusSeconds(70)));
    }

    @Test
    void redactsResolvedCredentialFromConnectionDiagnostics() {
        McpServerConnection connection = new McpServerConnection(
                "server-1", "STREAMABLE_HTTP", "https://mcp.example.com/v1", "", List.of(), "resolved-secret"
        );

        assertFalse(connection.toString().contains("resolved-secret"));
        assertTrue(connection.toString().contains("credentialConfigured=true"));
    }

    private boolean bindOnce(McpControlPlaneService service, ControlPlanePrincipal admin, Map<String, Object> tool) {
        try {
            service.bindAgent(admin, null, Map.of("agentCode", "same-agent", "toolVersionId", tool.get("latestVersionId")));
            return true;
        } catch (McpBindingConflictException exception) {
            return false;
        }
    }

    private McpDiscoveredTool readTool(String type) {
        return new McpDiscoveredTool("orders.lookup", "lookup", Map.of("type", "object", "properties", Map.of("id", Map.of("type", type))), Map.of("type", "object"), "LOW", false);
    }

    private Fixture fixture(List<McpDiscoveredTool> tools) {
        return fixture(new AtomicReference<>(tools));
    }

    private Fixture fixture(AtomicReference<List<McpDiscoveredTool>> tools) {
        InMemoryControlPlaneRepository repository = new InMemoryControlPlaneRepository();
        SecretRefResolver resolver = (type, reference) -> "MCP_API_KEY".equals(reference) ? java.util.Optional.of("mcp-resolved-token") : java.util.Optional.empty();
        ControlPlaneService control = new ControlPlaneService(repository, resolver, ProviderConnectionTester.unavailable(), Set.of("mcp.example.com"), Duration.ofSeconds(1));
        McpTransportClient transport = new McpTransportClient() {
            @Override public McpTransportResult test(McpServerConnection server, Duration timeout) { return new McpTransportResult("CONNECTED", "connected"); }
            @Override public List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout) { return tools.get(); }
            @Override
            public McpTransportCallResult call(
                    McpServerConnection server,
                    String toolName,
                    Map<String, Object> arguments,
                    Duration timeout
            ) {
                return new McpTransportCallResult(McpTransportCallResult.OK, Map.of("ok", true), null);
            }
        };
        return new Fixture(control, new McpControlPlaneService(control, repository, resolver, transport, Set.of("mcp.example.com"), Set.of(), false, Duration.ofSeconds(1)), control.principal("admin", "ADMIN"), control.principal("viewer", "VIEWER"));
    }

    private record Fixture(ControlPlaneService control, McpControlPlaneService mcp, ControlPlanePrincipal admin, ControlPlanePrincipal viewer) { }
}
