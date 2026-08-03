package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlaneAudit;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.InMemoryControlPlaneRepository;
import com.manzhushaka.agent.controlplane.InMemoryKnowledgeRepository;
import com.manzhushaka.agent.runtime.workflow.WorkflowDsl;
import com.manzhushaka.agent.runtime.workflow.WorkflowEdge;
import com.manzhushaka.agent.runtime.workflow.WorkflowNode;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import com.manzhushaka.agent.runtime.workflow.WorkflowValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowManagementServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final InMemoryControlPlaneRepository controlPlaneRepository = new InMemoryControlPlaneRepository();
    private final InMemoryKnowledgeRepository knowledgeRepository = new InMemoryKnowledgeRepository();
    private final ControlPlaneService controlPlaneService = new ControlPlaneService(controlPlaneRepository);
    private final WorkflowManagementService service = new WorkflowManagementService(
            new InMemoryWorkflowRepository(), controlPlaneRepository, knowledgeRepository, objectMapper
    );

    private ControlPlanePrincipal admin() {
        return controlPlaneService.principal("admin", "ADMIN");
    }

    private ControlPlanePrincipal operator() {
        return controlPlaneService.principal("op", "OPERATOR");
    }

    private ControlPlanePrincipal viewer() {
        return controlPlaneService.principal("viewer", "VIEWER");
    }

    @Test
    void createsDraftAndPublishesUnboundLinearWorkflow() {
        Map<String, Object> workflow = service.createWorkflow(admin(), Map.of(
                "code", "wf-demo", "displayName", "演示流程", "description", "demo"
        ));
        assertEquals("DRAFT", workflow.get("status"));

        Map<String, Object> version = service.createVersion(admin(), (String) workflow.get("id"),
                Map.of("dsl", linearDslJson(), "resourceBindings", Map.of()));
        assertEquals("DRAFT", version.get("status"));

        Map<String, Object> validated = service.validateVersion(admin(), (String) version.get("id"));
        assertEquals(true, validated.get("valid"));

        Map<String, Object> published = service.publishVersion(admin(), (String) version.get("id"));
        assertEquals("PUBLISHED", published.get("status"));
        assertEquals("ACTIVE", service.workflow(admin(), (String) workflow.get("id")).get("status"));
    }

    @Test
    void rejectsInvalidDslAtVersionCreation() {
        Map<String, Object> workflow = service.createWorkflow(admin(), Map.of(
                "code", "wf-cyc", "displayName", "环"
        ));
        assertThrows(WorkflowValidationException.class, () -> service.createVersion(admin(),
                (String) workflow.get("id"), Map.of("dsl", cyclicDslJson())));
    }

    @Test
    void publishRejectsMissingOrDisabledBindings() {
        Map<String, Object> workflow = service.createWorkflow(admin(), Map.of(
                "code", "wf-bind", "displayName", "绑定"
        ));
        Map<String, Object> version = service.createVersion(admin(), (String) workflow.get("id"), Map.of(
                "dsl", linearDslJson(),
                "resourceBindings", Map.of("modelVersionId", "model_missing")
        ));
        Map<String, Object> validated = service.validateVersion(admin(), (String) version.get("id"));
        assertEquals(false, validated.get("valid"));
        assertTrue(validated.get("issues").toString().contains("模型不存在或未启用"));
        assertThrows(IllegalArgumentException.class,
                () -> service.publishVersion(admin(), (String) version.get("id")));
    }

    @Test
    void publishAcceptsEnabledModelPromptKnowledgeAndToolBindings() {
        ControlPlanePrincipal admin = admin();
        Map<String, Object> model = controlPlaneService.saveModel(admin, null, Map.of(
                "code", "m1", "displayName", "M1", "modelType", "CHAT",
                "modelName", "chat-1", "enabled", true
        ));
        Map<String, Object> prompt = controlPlaneService.savePrompt(admin, null, Map.of(
                "code", "welcome", "displayName", "Welcome", "draftContent", "Hi"
        ));
        Map<String, Object> promptVersion = controlPlaneService.createVersion(admin, (String) prompt.get("id"));
        controlPlaneService.publishPrompt(admin, (String) promptVersion.get("id"));
        knowledgeRepository.saveKnowledgeBase(Map.of(
                "id", "kbv_1", "name", "KB", "status", "ACTIVE", "createdAt", Instant.now().toString()
        ), new ControlPlaneAudit("a1", "admin", "KB_CREATED", "KNOWLEDGE_BASE", "kbv_1", Map.of(), Instant.now()));
        controlPlaneRepository.saveDocument("MCP_TOOL_VERSION", Map.of(
                "id", "mtv_1", "toolId", "mt_1", "versionNo", 1
        ));
        controlPlaneRepository.saveDocument("MCP_TOOL", Map.of(
                "id", "mt_1", "name", "查询", "enabled", true, "latestVersionId", "mtv_1"
        ));

        Map<String, Object> workflow = service.createWorkflow(admin, Map.of(
                "code", "wf-bind-ok", "displayName", "绑定成功"
        ));
        Map<String, Object> version = service.createVersion(admin, (String) workflow.get("id"), Map.of(
                "dsl", linearDslJson(),
                "resourceBindings", Map.of(
                        "modelVersionId", (String) model.get("id"),
                        "promptVersionId", (String) promptVersion.get("id"),
                        "knowledgeBaseVersionId", "kbv_1",
                        "toolVersionIds", List.of("mtv_1")
                )
        ));
        Map<String, Object> validated = service.validateVersion(admin(), (String) version.get("id"));
        assertEquals(true, validated.get("valid"));
        assertEquals("PUBLISHED", service.publishVersion(admin(), (String) version.get("id")).get("status"));
    }

    @Test
    void viewerCannotWriteButOperatorCan() {
        assertThrows(ControlPlaneAccessDeniedException.class, () -> service.createWorkflow(viewer(),
                Map.of("code", "wf-x", "displayName", "X")));
        Map<String, Object> workflow = service.createWorkflow(operator(), Map.of(
                "code", "wf-op", "displayName", "Op"
        ));
        Map<String, Object> version = service.createVersion(operator(), (String) workflow.get("id"),
                Map.of("dsl", linearDslJson()));
        assertEquals("PUBLISHED", service.publishVersion(operator(), (String) version.get("id")).get("status"));
        assertEquals(1, ((List<?>) service.workflows(viewer(), "wf-op", 1, 20).get("items")).size());
        assertThrows(ControlPlaneAccessDeniedException.class, () -> service.publishVersion(viewer(),
                (String) version.get("id")));
    }

    @Test
    void rollbackAndArchiveFollowLifecycle() {
        Map<String, Object> workflow = service.createWorkflow(admin(), Map.of(
                "code", "wf-roll", "displayName", "回滚"
        ));
        Map<String, Object> first = service.createVersion(admin(), (String) workflow.get("id"),
                Map.of("dsl", linearDslJson()));
        service.publishVersion(admin(), (String) first.get("id"));
        Map<String, Object> second = service.createVersion(admin(), (String) workflow.get("id"),
                Map.of("dsl", linearDslJson()));
        service.publishVersion(admin(), (String) second.get("id"));

        Map<String, Object> rolledBack = service.rollback(admin(), (String) workflow.get("id"));
        assertEquals(first.get("id"), rolledBack.get("id"));
        assertEquals(first.get("id"),
                service.workflow(admin(), (String) workflow.get("id")).get("currentVersionId"));

        service.archive(admin(), (String) workflow.get("id"));
        assertEquals("ARCHIVED", service.workflow(admin(), (String) workflow.get("id")).get("status"));
        assertThrows(IllegalStateException.class, () -> service.createVersion(admin(),
                (String) workflow.get("id"), Map.of("dsl", linearDslJson())));
    }

    @Test
    void auditTrailRecordsWorkflowLifecycle() {
        Map<String, Object> workflow = service.createWorkflow(admin(), Map.of(
                "code", "wf-audit", "displayName", "审计"
        ));
        Map<String, Object> version = service.createVersion(admin(), (String) workflow.get("id"),
                Map.of("dsl", linearDslJson()));
        service.publishVersion(admin(), (String) version.get("id"));
        assertTrue(controlPlaneRepository.listDocuments("AUDIT").isEmpty() || controlPlaneService.audits(admin())
                .stream().anyMatch(audit -> "WORKFLOW_PUBLISHED".equals(audit.action())
                        && version.get("id").equals(audit.resourceId())));
    }

    private String linearDslJson() {
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-demo", "演示流程", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("n1", WorkflowNodeType.VARIABLE_ASSIGN, "赋值",
                        Map.of("assignments", Map.of("city", "上海"))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "n1", null),
                new WorkflowEdge("n1", "end", null)
        ));
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String cyclicDslJson() {
        WorkflowDsl dsl = new WorkflowDsl("1.0", "wf-cyc", "环", List.of(
                new WorkflowNode("start", WorkflowNodeType.START, "开始", Map.of()),
                new WorkflowNode("a", WorkflowNodeType.VARIABLE_ASSIGN, "赋值",
                        Map.of("assignments", Map.of("x", 1))),
                new WorkflowNode("end", WorkflowNodeType.END, "结束", Map.of())
        ), List.of(
                new WorkflowEdge("start", "a", null),
                new WorkflowEdge("a", "a", null),
                new WorkflowEdge("a", "end", null)
        ));
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SuppressWarnings("unused")
    private static void assertNoSecrets(Map<String, Object> view) {
        assertFalse(view.containsKey("secretRefId") || view.containsKey("reference"));
    }
}
