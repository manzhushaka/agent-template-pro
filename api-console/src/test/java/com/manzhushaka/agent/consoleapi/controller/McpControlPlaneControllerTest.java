package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.InMemoryControlPlaneRepository;
import com.manzhushaka.agent.controlplane.McpControlPlaneService;
import com.manzhushaka.agent.controlplane.McpDiscoveredTool;
import com.manzhushaka.agent.controlplane.McpServerConnection;
import com.manzhushaka.agent.controlplane.McpTransportClient;
import com.manzhushaka.agent.controlplane.McpTransportResult;
import com.manzhushaka.agent.controlplane.ProviderConnectionTester;
import com.manzhushaka.agent.controlplane.SecretRefResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpControlPlaneControllerTest {
    @Test
    void mapsWriteToolDebugToConflictWithoutExecutingIt() {
        InMemoryControlPlaneRepository repository = new InMemoryControlPlaneRepository();
        ControlPlaneService control = new ControlPlaneService(
                repository, SecretRefResolver.unavailable(), ProviderConnectionTester.unavailable(), Set.of(), Duration.ofSeconds(1)
        );
        McpTransportClient transport = new McpTransportClient() {
            @Override
            public McpTransportResult test(McpServerConnection server, Duration timeout) {
                return new McpTransportResult("CONNECTED", "connected");
            }

            @Override
            public List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout) {
                return List.of(new McpDiscoveredTool("orders.delete", "delete", Map.of(), Map.of(), "HIGH", true));
            }

            @Override
            public com.manzhushaka.agent.controlplane.McpTransportCallResult call(
                    McpServerConnection server,
                    String toolName,
                    Map<String, Object> arguments,
                    Duration timeout
            ) {
                return new com.manzhushaka.agent.controlplane.McpTransportCallResult(
                        com.manzhushaka.agent.controlplane.McpTransportCallResult.OK,
                        Map.of("ok", true), null);
            }
        };
        McpControlPlaneService service = new McpControlPlaneService(
                control, repository, SecretRefResolver.unavailable(), transport,
                Set.of("mcp.example.com"), Set.of(), false, Duration.ofSeconds(1)
        );
        ControlPlanePrincipal admin = control.principal("admin", "ADMIN");
        Map<String, Object> server = service.saveServer(admin, null, Map.of(
                "code", "orders", "transport", "STREAMABLE_HTTP", "endpoint", "https://mcp.example.com/v1", "enabled", true
        ));
        service.syncServer(admin, (String) server.get("id"));
        String toolId = (String) service.tools(admin, null, null).getFirst().get("id");
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePrincipal(any())).thenReturn(admin);

        WebTestClient.bindToController(new McpControlPlaneController(authentication, service)).build()
                .post().uri("/api/console/v1/mcp-tools/{id}:debug", toolId)
                .header("Authorization", "Bearer test")
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("MCP_WRITE_TOOL_CONFIRMATION_REQUIRED");
    }
}
