package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlaneAudit;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneRepository;
import com.manzhushaka.agent.controlplane.KnowledgeRepository;
import com.manzhushaka.agent.runtime.workflow.WorkflowCompiledGraph;
import com.manzhushaka.agent.runtime.workflow.WorkflowDsl;
import com.manzhushaka.agent.runtime.workflow.WorkflowDslValidator;
import com.manzhushaka.agent.runtime.workflow.WorkflowValidationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Workflow definition and version management. DSL snapshots are validated with the runtime
 * validator before a version can be published; bindings pin model, prompt, knowledge base and
 * MCP tool versions so a published run always executes against immutable resources.
 */
@Service
public class WorkflowManagementService {
    public static final String WORKFLOW_READ = "workflow:read";
    public static final String WORKFLOW_WRITE = "workflow:write";
    public static final String WORKFLOW_RUN = "workflow:run";

    private final WorkflowRepository repository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final ObjectMapper objectMapper;

    public WorkflowManagementService(
            WorkflowRepository repository,
            ControlPlaneRepository controlPlaneRepository,
            KnowledgeRepository knowledgeRepository,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.controlPlaneRepository = controlPlaneRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> createWorkflow(ControlPlanePrincipal principal, Map<String, Object> input) {
        require(principal, WORKFLOW_WRITE);
        String code = required(input, "code");
        if (repository.workflowByCode(code).isPresent()) {
            throw new IllegalArgumentException("Workflow code 已存在: " + code);
        }
        Instant now = Instant.now();
        WorkflowDefinition workflow = new WorkflowDefinition(
                "wfo_" + UUID.randomUUID(), code, required(input, "displayName"),
                optional(input, "description"), "DRAFT", null, principal.username(), now, now
        );
        repository.saveWorkflow(workflow);
        appendAudit(principal, "WORKFLOW_CREATED", workflow.id(), Map.of("code", workflow.code()));
        return workflowView(workflow);
    }

    public Map<String, Object> updateWorkflow(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, WORKFLOW_WRITE);
        WorkflowDefinition current = requireWorkflow(id);
        WorkflowDefinition updated = new WorkflowDefinition(
                current.id(), current.code(), defaulted(input, "displayName", current.displayName()),
                input.containsKey("description") ? String.valueOf(input.get("description")) : current.description(),
                current.status(), current.currentVersionId(), current.createdBy(),
                current.createdAt(), Instant.now()
        );
        repository.saveWorkflow(updated);
        appendAudit(principal, "WORKFLOW_UPDATED", updated.id(), Map.of("code", updated.code()));
        return workflowView(updated);
    }

    public Map<String, Object> workflow(ControlPlanePrincipal principal, String id) {
        require(principal, WORKFLOW_READ);
        return workflowView(requireWorkflow(id));
    }

    public Map<String, Object> workflows(ControlPlanePrincipal principal, String keyword, int page, int size) {
        require(principal, WORKFLOW_READ);
        List<Map<String, Object>> items = repository.workflows(keyword, page, size).stream()
                .map(this::workflowView).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", repository.countWorkflows(keyword));
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    public Map<String, Object> createVersion(
            ControlPlanePrincipal principal,
            String workflowId,
            Map<String, Object> input
    ) {
        require(principal, WORKFLOW_WRITE);
        WorkflowDefinition workflow = requireWorkflow(workflowId);
        if ("ARCHIVED".equals(workflow.status())) {
            throw new IllegalStateException("已归档的 Workflow 不能创建版本。");
        }
        WorkflowDsl dsl = dsl(input);
        WorkflowDslValidator.validate(dsl);
        Map<String, Object> bindings = bindings(input);
        int versionNo = repository.nextVersionNo(workflowId);
        Instant now = Instant.now();
        WorkflowVersion version = new WorkflowVersion(
                "wfv_" + UUID.randomUUID(), workflowId, versionNo, "DRAFT",
                dsl.schemaVersion(), dslJson(dsl), bindings, optional(input, "description"),
                principal.username(), null, now, now
        );
        repository.saveVersion(version);
        appendAudit(principal, "WORKFLOW_VERSION_CREATED", version.id(),
                Map.of("workflowId", workflowId, "versionNo", versionNo));
        return versionView(version);
    }

    public Map<String, Object> validateVersion(ControlPlanePrincipal principal, String versionId) {
        require(principal, WORKFLOW_WRITE);
        WorkflowVersion version = requireVersion(versionId);
        List<Map<String, Object>> issues = new ArrayList<>();
        try {
            WorkflowDslValidator.validate(parseDsl(version.dslJson()));
        } catch (WorkflowValidationException exception) {
            issues.add(Map.of("type", "DSL", "message", exception.getMessage()));
        }
        issues.addAll(validateBindings(version.resourceBindings()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("versionId", versionId);
        result.put("valid", issues.isEmpty());
        result.put("issues", issues);
        return result;
    }

    public Map<String, Object> publishVersion(ControlPlanePrincipal principal, String versionId) {
        require(principal, WORKFLOW_WRITE);
        WorkflowVersion version = requireVersion(versionId);
        if (!"DRAFT".equals(version.status())) {
            throw new IllegalStateException("只有 DRAFT 版本可以发布。");
        }
        WorkflowDslValidator.validate(parseDsl(version.dslJson()));
        List<Map<String, Object>> issues = validateBindings(version.resourceBindings());
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("发布前校验失败: " + issues);
        }
        Instant now = Instant.now();
        WorkflowVersion published = new WorkflowVersion(
                version.id(), version.workflowId(), version.versionNo(), "PUBLISHED",
                version.schemaVersion(), version.dslJson(), version.resourceBindings(),
                version.description(), version.createdBy(), now, version.createdAt(), now
        );
        repository.saveVersion(published);
        WorkflowDefinition current = requireWorkflow(version.workflowId());
        repository.saveWorkflow(new WorkflowDefinition(
                current.id(), current.code(), current.displayName(), current.description(),
                "ACTIVE", published.id(), current.createdBy(), current.createdAt(), now
        ));
        appendAudit(principal, "WORKFLOW_PUBLISHED", published.id(),
                Map.of("workflowId", published.workflowId(), "versionNo", published.versionNo()));
        return versionView(published);
    }

    public Map<String, Object> rollback(ControlPlanePrincipal principal, String workflowId) {
        require(principal, WORKFLOW_WRITE);
        WorkflowDefinition workflow = requireWorkflow(workflowId);
        List<WorkflowVersion> published = repository.versions(workflowId).stream()
                .filter(version -> "PUBLISHED".equals(version.status()))
                .sorted((left, right) -> Integer.compare(right.versionNo(), left.versionNo()))
                .toList();
        if (published.size() < 2) {
            throw new IllegalStateException("没有可回滚的历史发布版本。");
        }
        WorkflowVersion target = published.get(1);
        Instant now = Instant.now();
        repository.saveWorkflow(new WorkflowDefinition(
                workflow.id(), workflow.code(), workflow.displayName(), workflow.description(),
                "ACTIVE", target.id(), workflow.createdBy(), workflow.createdAt(), now
        ));
        appendAudit(principal, "WORKFLOW_ROLLED_BACK", target.id(),
                Map.of("workflowId", workflowId, "targetVersionNo", target.versionNo(),
                        "previousVersionNo", published.getFirst().versionNo()));
        return versionView(target);
    }

    public Map<String, Object> archive(ControlPlanePrincipal principal, String workflowId) {
        require(principal, WORKFLOW_WRITE);
        WorkflowDefinition workflow = requireWorkflow(workflowId);
        Instant now = Instant.now();
        repository.saveWorkflow(new WorkflowDefinition(
                workflow.id(), workflow.code(), workflow.displayName(), workflow.description(),
                "ARCHIVED", workflow.currentVersionId(), workflow.createdBy(),
                workflow.createdAt(), now
        ));
        appendAudit(principal, "WORKFLOW_ARCHIVED", workflow.id(), Map.of("code", workflow.code()));
        return workflowView(repository.workflow(workflowId).orElse(workflow));
    }

    public Map<String, Object> version(ControlPlanePrincipal principal, String versionId) {
        require(principal, WORKFLOW_READ);
        return versionView(requireVersion(versionId));
    }

    public List<Map<String, Object>> versions(ControlPlanePrincipal principal, String workflowId) {
        require(principal, WORKFLOW_READ);
        requireWorkflow(workflowId);
        return repository.versions(workflowId).stream().map(this::versionView).toList();
    }

    private List<Map<String, Object>> validateBindings(Map<String, Object> bindings) {
        List<Map<String, Object>> issues = new ArrayList<>();
        String modelVersionId = string(bindings.get("modelVersionId"));
        String promptVersionId = string(bindings.get("promptVersionId"));
        String knowledgeBaseVersionId = string(bindings.get("knowledgeBaseVersionId"));
        Object toolsValue = bindings.get("toolVersionIds");
        List<String> toolVersionIds = toolsValue instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (!modelVersionId.isBlank()) {
            boolean ok = controlPlaneRepository.listDocuments("MODEL").stream()
                    .anyMatch(model -> modelVersionId.equals(model.get("id"))
                            && !Boolean.FALSE.equals(model.get("enabled")));
            if (!ok) {
                issues.add(issue("MODEL", modelVersionId, "模型不存在或未启用。"));
            }
        }
        if (!promptVersionId.isBlank()) {
            boolean ok = controlPlaneRepository.listDocuments("PROMPT").stream()
                    .anyMatch(prompt -> promptVersionId.equals(prompt.get("publishedVersionId")))
                    && controlPlaneRepository.listDocuments("PROMPT_VERSION").stream()
                    .anyMatch(version -> promptVersionId.equals(version.get("id")));
            if (!ok) {
                issues.add(issue("PROMPT_VERSION", promptVersionId, "Prompt 版本未发布或不存在。"));
            }
        }
        if (!knowledgeBaseVersionId.isBlank()) {
            Optional<Map<String, Object>> knowledgeBase = knowledgeRepository.findKnowledgeBase(knowledgeBaseVersionId);
            if (knowledgeBase.isEmpty() || !"ACTIVE".equals(knowledgeBase.get().get("status"))) {
                issues.add(issue("KNOWLEDGE_BASE", knowledgeBaseVersionId, "知识库不存在或未启用。"));
            }
        }
        List<Map<String, Object>> toolVersions = controlPlaneRepository.listMcpResources("MCP_TOOL_VERSION");
        List<Map<String, Object>> tools = controlPlaneRepository.listMcpResources("MCP_TOOL");
        for (String toolVersionId : toolVersionIds) {
            boolean ok = toolVersions.stream().anyMatch(version -> toolVersionId.equals(version.get("id")))
                    && tools.stream().anyMatch(tool -> toolVersionId.equals(tool.get("latestVersionId"))
                            && Boolean.TRUE.equals(tool.get("enabled")));
            if (!ok) {
                issues.add(issue("MCP_TOOL_VERSION", toolVersionId, "MCP Tool 版本不存在、未启用或不是最新版本。"));
            }
        }
        return List.copyOf(issues);
    }

    private WorkflowDsl dsl(Map<String, Object> input) {
        Object dslValue = input.get("dsl");
        if (dslValue == null) {
            throw new IllegalArgumentException("缺少 dsl");
        }
        if (dslValue instanceof Map<?, ?> map) {
            return objectMapper.convertValue(map, WorkflowDsl.class);
        }
        if (dslValue instanceof String text) {
            return parseDsl(text);
        }
        throw new IllegalArgumentException("dsl 必须是对象或 JSON 字符串");
    }

    private WorkflowDsl parseDsl(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDsl.class);
        } catch (Exception exception) {
            throw new WorkflowValidationException("Workflow DSL 不是合法 JSON: " + exception.getMessage());
        }
    }

    private String dslJson(WorkflowDsl dsl) {
        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Workflow DSL 无法序列化", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bindings(Map<String, Object> input) {
        Object value = input.get("resourceBindings");
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private WorkflowDefinition requireWorkflow(String id) {
        return repository.workflow(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow 不存在: " + id));
    }

    private WorkflowVersion requireVersion(String id) {
        return repository.version(id)
                .orElseThrow(() -> new IllegalArgumentException("Workflow 版本不存在: " + id));
    }

    private Map<String, Object> workflowView(WorkflowDefinition workflow) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", workflow.id());
        view.put("code", workflow.code());
        view.put("displayName", workflow.displayName());
        view.put("description", workflow.description());
        view.put("status", workflow.status());
        view.put("currentVersionId", workflow.currentVersionId());
        view.put("createdBy", workflow.createdBy());
        view.put("createdAt", workflow.createdAt());
        view.put("updatedAt", workflow.updatedAt());
        return view;
    }

    private Map<String, Object> versionView(WorkflowVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id());
        view.put("workflowId", version.workflowId());
        view.put("versionNo", version.versionNo());
        view.put("status", version.status());
        view.put("schemaVersion", version.schemaVersion());
        view.put("dsl", parseDsl(version.dslJson()));
        view.put("resourceBindings", version.resourceBindings());
        view.put("description", version.description());
        view.put("createdBy", version.createdBy());
        view.put("publishedAt", version.publishedAt());
        view.put("createdAt", version.createdAt());
        view.put("updatedAt", version.updatedAt());
        return view;
    }

    private void appendAudit(ControlPlanePrincipal principal, String action, String resourceId, Map<String, Object> metadata) {
        ControlPlaneAudit audit = new ControlPlaneAudit(
                UUID.randomUUID().toString(), principal.username(), action, "WORKFLOW",
                resourceId, metadata, Instant.now()
        );
        controlPlaneRepository.appendAudit(audit);
    }

    private void require(ControlPlanePrincipal principal, String permission) {
        if (principal == null || !principal.permissions().contains(permission)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }

    private static String required(Map<String, Object> input, String key) {
        String value = string(input.get(key));
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return value;
    }

    private static String optional(Map<String, Object> input, String key) {
        Object value = input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String defaulted(Map<String, Object> input, String key, String fallback) {
        return input.containsKey(key) ? String.valueOf(input.get(key)) : fallback;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> issue(String type, String resourceId, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resourceType", type);
        result.put("resourceId", resourceId);
        result.put("message", message);
        return result;
    }
}
