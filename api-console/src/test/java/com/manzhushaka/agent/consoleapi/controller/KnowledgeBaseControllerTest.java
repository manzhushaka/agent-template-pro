package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.InMemoryKnowledgeRepository;
import com.manzhushaka.agent.controlplane.InMemoryObjectStorage;
import com.manzhushaka.agent.controlplane.InMemoryVectorStore;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseControllerTest {
    @Test
    void managesKnowledgeSourcesWithPaginationAndDoesNotExposeChunkContent() {
        ControlPlaneService controlPlaneService = new ControlPlaneService();
        ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
        KnowledgeBaseService service = new KnowledgeBaseService(
                new InMemoryKnowledgeRepository(), new InMemoryObjectStorage(), new InMemoryVectorStore()
        );
        ConsoleAuthenticationService authentication = authenticatedAs(admin);
        WebTestClient client = WebTestClient.bindToController(new KnowledgeBaseController(authentication, service)).build();

        client.post().uri("/api/console/v1/knowledge-bases")
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("code", "handbook", "displayName", "员工手册"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.code").isEqualTo("handbook")
                .jsonPath("$.id").exists();

        String createdId = service.knowledgeBases(admin).getFirst().get("id").toString();
        client.get().uri(uriBuilder -> uriBuilder.path("/api/console/v1/knowledge-bases")
                        .queryParam("keyword", "hand").queryParam("page", 1).queryParam("size", 1).build())
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(createdId);

        client.post().uri("/api/console/v1/knowledge-bases/{id}/documents", createdId)
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("name", "handbook.md", "contentType", "text/markdown", "content", "内部资料正文不得在 Console 回显"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("QUEUED");

        String documentId = service.documents(admin, createdId).getFirst().get("id").toString();

        service.processQueuedJobs("test-worker", 1);
        client.get().uri("/api/console/v1/documents/{id}/chunks:preview", documentId)
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].content").doesNotExist()
                .jsonPath("$.items[0].enabled").isEqualTo(true);

        client.post().uri("/api/console/v1/knowledge-bases/{id}:retrieve-test", createdId)
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("query", "内部资料", "topK", 5, "threshold", 0))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].citation.content").doesNotExist()
                .jsonPath("$[0].citation.documentId").isEqualTo(documentId);
    }

    @Test
    void mapsMissingKnowledgeWritePermissionToStableForbiddenResponse() {
        ControlPlaneService controlPlaneService = new ControlPlaneService();
        ControlPlanePrincipal viewer = controlPlaneService.principal("viewer", "VIEWER");
        KnowledgeBaseService service = new KnowledgeBaseService(
                new InMemoryKnowledgeRepository(), new InMemoryObjectStorage(), new InMemoryVectorStore()
        );
        WebTestClient.bindToController(new KnowledgeBaseController(authenticatedAs(viewer), service)).build()
                .post().uri("/api/console/v1/knowledge-bases")
                .header("Authorization", "Bearer viewer")
                .bodyValue(Map.of("code", "blocked", "displayName", "不允许"))
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_PERMISSION_DENIED");
    }

    @Test
    void mapsExpiredConsoleSessionToStableUnauthorizedResponse() {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePrincipal(any())).thenThrow(new ConsoleAuthenticationException(
                ConsoleAuthenticationException.Reason.SESSION_INVALID
        ));
        KnowledgeBaseService service = new KnowledgeBaseService(
                new InMemoryKnowledgeRepository(), new InMemoryObjectStorage(), new InMemoryVectorStore()
        );

        WebTestClient.bindToController(new KnowledgeBaseController(authentication, service)).build()
                .get().uri("/api/console/v1/knowledge-bases")
                .header("Authorization", "Bearer expired")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_SESSION_INVALID");
    }

    @Test
    void mapsDuplicateKnowledgeResourcesToStableConflictResponse() {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePrincipal(any())).thenReturn(new ControlPlanePrincipal(
                "admin", "ADMIN", java.util.Set.of(ControlPlaneService.KNOWLEDGE_READ, ControlPlaneService.KNOWLEDGE_WRITE)
        ));
        KnowledgeBaseService service = mock(KnowledgeBaseService.class);
        when(service.saveKnowledgeBase(any(), any(), any())).thenThrow(new DuplicateKeyException("duplicate key"));

        WebTestClient.bindToController(new KnowledgeBaseController(authentication, service)).build()
                .post().uri("/api/console/v1/knowledge-bases")
                .header("Authorization", "Bearer admin")
                .bodyValue(Map.of("code", "duplicate", "displayName", "Duplicate"))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("KNOWLEDGE_RESOURCE_CONFLICT")
                .jsonPath("$.message").isEqualTo("知识库编码或索引资源已存在。");
    }

    @Test
    void rejectsDecodedDocumentsThatExceedTheTenMegabyteContract() {
        String oversized = Base64.getEncoder().encodeToString(new byte[10 * 1024 * 1024 + 1]);
        KnowledgeBaseController.DocumentUploadRequest request = new KnowledgeBaseController.DocumentUploadRequest(
                "oversized.pdf", "application/pdf", null, oversized
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, request::bytes);

        assertEquals("文档为空或超过 10 MB 限制。", exception.getMessage());
    }

    private ConsoleAuthenticationService authenticatedAs(ControlPlanePrincipal principal) {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePrincipal(any())).thenReturn(principal);
        return authentication;
    }
}
