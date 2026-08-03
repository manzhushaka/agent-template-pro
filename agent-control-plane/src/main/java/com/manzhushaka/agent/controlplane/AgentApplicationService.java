package com.manzhushaka.agent.controlplane;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Agent application lifecycle: draft versions, immutable published snapshots, publish history,
 * hash-only API keys and a controlled OpenAPI document. Every resource referenced by a version
 * is validated against the current control-plane source of truth before publish.
 */
public final class AgentApplicationService {
    public static final String APP_READ = "agentapp:read";
    public static final String APP_WRITE = "agentapp:write";
    public static final String APP_PUBLISH = "agentapp:publish";
    public static final String APIKEY_READ = "apikey:read";
    public static final String APIKEY_WRITE = "apikey:write";
    public static final String DEFAULT_SCOPE = "chat:completions";

    private static final int KEY_BYTES = 32;
    private static final String KEY_PREFIX = "ag_";
    private static final Set<String> APP_STATUSES = Set.of("DRAFT", "ACTIVE", "ARCHIVED");
    private static final Set<String> BINDING_TYPES = Set.of("MCP_TOOL_VERSION", "MCP_SERVER", "KNOWLEDGE");
    private static final int MAX_NAME_LENGTH = 160;

    private final AgentApplicationRepository repository;
    private final ControlPlaneRepository controlPlaneRepository;
    private final KnowledgeRepository knowledgeRepository;
    private final String activeModelCode;
    private final SecureRandom secureRandom = new SecureRandom();

    public AgentApplicationService(
            AgentApplicationRepository repository,
            ControlPlaneRepository controlPlaneRepository,
            KnowledgeRepository knowledgeRepository,
            String activeModelCode
    ) {
        this.repository = repository;
        this.controlPlaneRepository = controlPlaneRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.activeModelCode = activeModelCode == null || activeModelCode.isBlank() ? "" : activeModelCode.trim();
    }

    public List<Map<String, Object>> applications(ControlPlanePrincipal principal, String keyword) {
        require(principal, APP_READ);
        return repository.listApplications().stream()
                .filter(value -> keyword == null || keyword.isBlank() || matches(value, keyword, "code", "displayName"))
                .map(this::safeApplication)
                .toList();
    }

    public Map<String, Object> saveApplication(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, APP_WRITE);
        Map<String, Object> value = id == null ? new LinkedHashMap<>() : new LinkedHashMap<>(application(id));
        copy(input, value, "code", "displayName", "description", "status");
        value.put("id", id == null ? UUID.randomUUID().toString() : id);
        requireText(value, "code");
        requireText(value, "displayName");
        value.put("code", String.valueOf(value.get("code")).trim());
        value.put("displayName", String.valueOf(value.get("displayName")).trim());
        if (String.valueOf(value.get("displayName")).length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("应用名称过长。");
        }
        value.put("status", value.getOrDefault("status", "DRAFT"));
        if (!APP_STATUSES.contains(value.get("status"))) {
            throw new IllegalArgumentException("无效的应用状态。");
        }
        if ("ARCHIVED".equals(value.get("status"))) {
            throw new IllegalArgumentException("归档通过专用接口完成。");
        }
        Instant now = Instant.now();
        value.putIfAbsent("createdAt", now.toString());
        value.put("updatedAt", now.toString());
        repository.saveApplication(Map.copyOf(value),
                audit(principal, "AGENT_APP_SAVED", "AGENT_APPLICATION", value.get("id"), Map.of("code", value.get("code"))));
        return safeApplication(value);
    }

    public void archiveApplication(ControlPlanePrincipal principal, String applicationId) {
        require(principal, APP_WRITE);
        if (!repository.archiveApplication(applicationId, Instant.now(),
                audit(principal, "AGENT_APP_ARCHIVED", "AGENT_APPLICATION", applicationId, Map.of()))) {
            throw new IllegalStateException("应用不存在、已归档或仍被有效 API Key 引用。");
        }
    }

    public List<Map<String, Object>> versions(ControlPlanePrincipal principal, String applicationId) {
        require(principal, APP_READ);
        application(applicationId);
        return repository.listVersions(applicationId).stream().map(this::safeVersion).toList();
    }

    public Map<String, Object> version(ControlPlanePrincipal principal, String versionId) {
        require(principal, APP_READ);
        return safeVersion(version(versionId));
    }

    public List<Map<String, Object>> versionBindings(ControlPlanePrincipal principal, String versionId) {
        require(principal, APP_READ);
        version(versionId);
        return repository.listVersionBindings(versionId).stream().map(this::safeBinding).toList();
    }

    public Map<String, Object> createVersion(ControlPlanePrincipal principal, String applicationId, Map<String, Object> input) {
        require(principal, APP_WRITE);
        application(applicationId);
        String modelCode = requiredString(input, "modelCode");
        String promptId = requiredString(input, "promptId");
        String promptVersionId = requiredString(input, "promptVersionId");
        String knowledgeBaseId = optionalString(input, "knowledgeBaseId");
        Map<String, Object> config = input.get("config") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw) : Map.of();
        List<Map<String, Object>> requestedBindings = input.get("bindings") instanceof List<?> raw
                ? raw.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        validateVersionResources(modelCode, promptId, promptVersionId, knowledgeBaseId, requestedBindings);
        Instant now = Instant.now();
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> binding : requestedBindings) {
            bindings.add(Map.of(
                    "id", UUID.randomUUID().toString(),
                    "resourceType", String.valueOf(binding.get("resourceType")).toUpperCase(Locale.ROOT),
                    "resourceId", String.valueOf(binding.get("resourceId")),
                    "resourceVersion", optionalString(binding, "resourceVersion"),
                    "config", binding.get("config") == null ? Map.of() : binding.get("config"),
                    "createdAt", now.toString()));
        }
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", UUID.randomUUID().toString());
        version.put("applicationId", applicationId);
        version.put("status", "DRAFT");
        version.put("modelCode", modelCode);
        version.put("promptId", promptId);
        version.put("promptVersionId", promptVersionId);
        if (knowledgeBaseId != null) {
            version.put("knowledgeBaseId", knowledgeBaseId);
        }
        version.put("config", Map.copyOf(config));
        version.put("createdBy", principal.username());
        version.put("createdAt", now.toString());
        version.put("updatedAt", now.toString());
        return safeVersion(repository.createVersion(version, bindings,
                audit(principal, "AGENT_APP_VERSION_CREATED", "AGENT_APPLICATION_VERSION", version.get("id"),
                        Map.of("applicationId", applicationId))));
    }

    public Map<String, Object> validateVersion(ControlPlanePrincipal principal, String versionId) {
        require(principal, APP_READ);
        Map<String, Object> version = version(versionId);
        List<Map<String, Object>> issues = new ArrayList<>();
        if (!"DRAFT".equals(version.get("status")) && !"PUBLISHED".equals(version.get("status"))) {
            issues.add(issue("VERSION", versionId, "版本状态不允许发布。"));
        }
        issues.addAll(validateVersionResources(
                String.valueOf(version.get("modelCode")),
                String.valueOf(version.get("promptId")),
                String.valueOf(version.get("promptVersionId")),
                version.get("knowledgeBaseId") == null ? null : String.valueOf(version.get("knowledgeBaseId")),
                repository.listVersionBindings(versionId)));
        return Map.of(
                "versionId", versionId,
                "valid", issues.isEmpty(),
                "issues", List.copyOf(issues),
                "validatedAt", Instant.now().toString());
    }

    public Map<String, Object> publishVersion(ControlPlanePrincipal principal, String versionId) {
        require(principal, APP_PUBLISH);
        Map<String, Object> validation = validateVersion(principal, versionId);
        if (!Boolean.TRUE.equals(validation.get("valid"))) {
            throw new IllegalStateException("版本未通过发布校验，不能发布。");
        }
        Map<String, Object> version = version(versionId);
        Map<String, Object> app = application(String.valueOf(version.get("applicationId")));
        return safeVersion(repository.publishVersion(
                String.valueOf(version.get("applicationId")), versionId,
                app.get("currentVersionId") == null ? null : String.valueOf(app.get("currentVersionId")),
                audit(principal, "AGENT_APP_VERSION_PUBLISHED", "AGENT_APPLICATION_VERSION", versionId,
                        Map.of("applicationId", version.get("applicationId")))));
    }

    public Map<String, Object> rollbackApplication(ControlPlanePrincipal principal, String applicationId, String targetVersionId) {
        require(principal, APP_PUBLISH);
        Map<String, Object> app = application(applicationId);
        Map<String, Object> target = repository.listVersions(applicationId).stream()
                .filter(value -> targetVersionId.equals(value.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标版本不存在或不属于该应用。"));
        if (!"PUBLISHED".equals(target.get("status"))) {
            throw new IllegalStateException("只能回滚到已发布版本。");
        }
        return safeVersion(repository.rollbackVersion(
                applicationId, targetVersionId,
                app.get("currentVersionId") == null ? null : String.valueOf(app.get("currentVersionId")),
                audit(principal, "AGENT_APP_ROLLED_BACK", "AGENT_APPLICATION_VERSION", targetVersionId,
                        Map.of("applicationId", applicationId))));
    }

    public List<Map<String, Object>> publishRecords(ControlPlanePrincipal principal, String applicationId) {
        require(principal, APP_READ);
        application(applicationId);
        return repository.listPublishRecords(applicationId).stream().map(this::safePublishRecord).toList();
    }

    public List<Map<String, Object>> apiKeys(ControlPlanePrincipal principal, String applicationId) {
        require(principal, APIKEY_READ);
        application(applicationId);
        return repository.listApiKeys(applicationId).stream().map(this::safeApiKey).toList();
    }

    public Map<String, Object> createApiKey(
            ControlPlanePrincipal principal,
            String applicationId,
            Instant expiresAt,
            List<String> scopes
    ) {
        require(principal, APIKEY_WRITE);
        Map<String, Object> app = application(applicationId);
        if ("ARCHIVED".equals(app.get("status"))) {
            throw new IllegalStateException("已归档的应用不能创建 API Key。");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("API Key 过期时间必须晚于当前时间。");
        }
        List<String> normalizedScopes = scopes == null || scopes.isEmpty() ? List.of(DEFAULT_SCOPE) : scopes.stream().toList();
        String plaintext = KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
        Instant now = Instant.now();
        Map<String, Object> key = new LinkedHashMap<>();
        key.put("id", UUID.randomUUID().toString());
        key.put("applicationId", applicationId);
        key.put("keyHash", sha256(plaintext));
        // Display prefix is derived from the SHA-256 hash so no plaintext key material ever leaves
        // the service boundary.
        key.put("keyPrefix", "ag_" + sha256(plaintext).substring(0, 10));
        key.put("status", "ACTIVE");
        key.put("scopes", List.copyOf(normalizedScopes));
        if (expiresAt != null) {
            key.put("expiresAt", expiresAt.toString());
        }
        key.put("createdAt", now.toString());
        key.put("updatedAt", now.toString());
        repository.saveApiKey(key, audit(principal, "AGENT_API_KEY_CREATED", "AGENT_API_KEY", key.get("id"),
                Map.of("applicationId", applicationId)));
        Map<String, Object> response = new LinkedHashMap<>(safeApiKey(key));
        response.put("key", plaintext);
        return Map.copyOf(response);
    }

    public Map<String, Object> rotateApiKey(ControlPlanePrincipal principal, String applicationId, String keyId) {
        require(principal, APIKEY_WRITE);
        Map<String, Object> existing = repository.findApiKey(applicationId, keyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key 不存在或不属于该应用。"));
        if (!"ACTIVE".equals(existing.get("status"))) {
            throw new IllegalStateException("只有有效 API Key 可以轮换。");
        }
        Instant now = Instant.now();
        repository.revokeApiKey(applicationId, keyId, now,
                audit(principal, "AGENT_API_KEY_ROTATED", "AGENT_API_KEY", keyId, Map.of("applicationId", applicationId)));
        List<String> scopes = existing.get("scopes") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList() : List.of(DEFAULT_SCOPE);
        Map<String, Object> replacement = createApiKey(principal, applicationId,
                existing.get("expiresAt") == null ? null : Instant.parse(String.valueOf(existing.get("expiresAt"))),
                scopes);
        return Map.copyOf(replacement);
    }

    public void revokeApiKey(ControlPlanePrincipal principal, String applicationId, String keyId) {
        require(principal, APIKEY_WRITE);
        if (repository.revokeApiKey(applicationId, keyId, Instant.now(),
                audit(principal, "AGENT_API_KEY_REVOKED", "AGENT_API_KEY", keyId, Map.of("applicationId", applicationId))).isEmpty()) {
            throw new IllegalArgumentException("API Key 不存在、不属于该应用或已撤销。");
        }
    }

    public Map<String, Object> openApiSpec(ControlPlanePrincipal principal, String applicationId) {
        require(principal, APP_READ);
        Map<String, Object> app = application(applicationId);
        Map<String, Object> current = app.get("currentVersionId") == null ? null : version(String.valueOf(app.get("currentVersionId")));
        Map<String, Object> messageSchema = Map.of(
                "type", "object",
                "required", List.of("role", "content"),
                "properties", Map.of(
                        "role", Map.of("type", "string", "enum", List.of("user", "assistant")),
                        "content", Map.of("type", "string")));
        Map<String, Object> chatRequestSchema = Map.of(
                "type", "object",
                "required", List.of("messages"),
                "properties", Map.of(
                        "messages", Map.of("type", "array", "items", messageSchema),
                        "stream", Map.of("type", "boolean", "default", false)));
        Map<String, Object> choiceSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "index", Map.of("type", "integer"),
                        "message", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "role", Map.of("type", "string"),
                                        "content", Map.of("type", "string"))),
                        "finish_reason", Map.of("type", "string")));
        Map<String, Object> chatResponseSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string"),
                        "object", Map.of("type", "string"),
                        "model", Map.of("type", "string"),
                        "choices", Map.of("type", "array", "items", choiceSchema)));
        Map<String, Object> postOperation = Map.of(
                "operationId", "chatCompletions",
                "summary", "调用已发布 Agent 应用",
                "parameters", List.of(Map.of(
                        "name", "appCode",
                        "in", "path",
                        "required", true,
                        "schema", Map.of("type", "string"))),
                "requestBody", Map.of(
                        "required", true,
                        "content", Map.of("application/json", Map.of("schema", chatRequestSchema))),
                "responses", Map.of(
                        "200", Map.of(
                                "description", "完成响应",
                                "content", Map.of("application/json", Map.of("schema", chatResponseSchema))),
                        "401", Map.of("description", "API Key 无效或已过期"),
                        "409", Map.of("description", "应用未发布或模型未连接")));
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.0.3");
        spec.put("info", Map.of(
                "title", String.valueOf(app.get("displayName")),
                "description", String.valueOf(app.get("description") == null ? "" : app.get("description")),
                "version", current == null ? "draft" : String.valueOf(current.get("version"))));
        spec.put("servers", List.of(Map.of("url", "/api/agent/v1")));
        spec.put("security", List.of(Map.of("ApiKeyAuth", List.of())));
        spec.put("components", Map.of("securitySchemes", Map.of(
                "ApiKeyAuth", Map.of("type", "apiKey", "in", "header", "name", "X-API-Key"))));
        spec.put("paths", Map.of("/apps/{appCode}/chat/completions", Map.of("post", postOperation)));
        if (current != null) {
            spec.put("x-published-resources", publishedResources(applicationId, current));
        }
        return Map.copyOf(spec);
    }

    /** Resolves the currently published app for the open API after API-key authentication. */
    public PublishedAppSnapshot resolvePublishedAppForCall(String appCode) {
        Map<String, Object> app = repository.findApplicationByCode(appCode)
                .orElseThrow(() -> new IllegalArgumentException("AGENT_APP_NOT_FOUND"));
        if (!"ACTIVE".equals(app.get("status"))) {
            throw new IllegalStateException("AGENT_APP_NOT_PUBLISHED");
        }
        if (app.get("currentVersionId") == null) {
            throw new IllegalStateException("AGENT_APP_NOT_PUBLISHED");
        }
        Map<String, Object> version = version(String.valueOf(app.get("currentVersionId")));
        if (!"PUBLISHED".equals(version.get("status"))) {
            throw new IllegalStateException("AGENT_APP_NOT_PUBLISHED");
        }
        return new PublishedAppSnapshot(
                String.valueOf(app.get("id")),
                appCode,
                String.valueOf(app.get("displayName")),
                Map.copyOf(version),
                repository.listVersionBindings(String.valueOf(version.get("id"))).stream().map(this::safeBinding).toList());
    }

    /** Resolves the owning application code for a version, used by evaluation targets. */
    public String appCodeForVersion(String versionId) {
        Map<String, Object> version = repository.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("AGENT_VERSION_NOT_FOUND"));
        return repository.findApplication(String.valueOf(version.get("applicationId")))
                .map(app -> String.valueOf(app.get("code")))
                .orElseThrow(() -> new IllegalArgumentException("AGENT_APP_NOT_FOUND"));
    }

    public String authenticateApiKey(String appCode, String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            throw new IllegalArgumentException("AGENT_API_KEY_REQUIRED");
        }
        Map<String, Object> key = repository.findApiKeyByHash(sha256(presentedKey))
                .orElseThrow(() -> new IllegalArgumentException("AGENT_API_KEY_INVALID"));
        if (!"ACTIVE".equals(key.get("status"))) {
            throw new IllegalArgumentException("AGENT_API_KEY_REVOKED");
        }
        if (key.get("expiresAt") != null && !Instant.parse(String.valueOf(key.get("expiresAt"))).isAfter(Instant.now())) {
            throw new IllegalArgumentException("AGENT_API_KEY_EXPIRED");
        }
        Map<String, Object> app = repository.findApplication(String.valueOf(key.get("applicationId")))
                .orElseThrow(() -> new IllegalArgumentException("AGENT_APP_NOT_FOUND"));
        if (!appCode.equals(app.get("code"))) {
            throw new IllegalArgumentException("AGENT_API_KEY_INVALID");
        }
        List<?> scopes = key.get("scopes") instanceof List<?> raw ? raw : List.of();
        if (scopes.stream().noneMatch(scope -> DEFAULT_SCOPE.equals(String.valueOf(scope)))) {
            throw new IllegalArgumentException("AGENT_API_KEY_SCOPE_DENIED");
        }
        repository.recordApiKeyUsage(String.valueOf(key.get("id")), Instant.now());
        return String.valueOf(key.get("id"));
    }

    public String activeModelCode() {
        return activeModelCode;
    }

    /**
     * Returns the rendered content of a published prompt version. Only the prompt content is
     * returned; the control-plane prompt record stays inside this service boundary.
     */
    public String resolvePromptContent(String promptVersionId, Map<String, Object> variables) {
        Map<String, Object> version = controlPlaneRepository.listDocuments("PROMPT_VERSION").stream()
                .filter(item -> promptVersionId.equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("AGENT_PROMPT_VERSION_MISSING"));
        String content = String.valueOf(version.getOrDefault("content", ""));
        Map<String, Object> resolved = variables == null ? Map.of() : variables;
        for (Map.Entry<String, Object> entry : resolved.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", String.valueOf(entry.getValue()));
        }
        if (content.length() > 32_000) {
            throw new IllegalStateException("AGENT_PROMPT_TOO_LARGE");
        }
        return content;
    }

    private Map<String, Object> publishedResources(String applicationId, Map<String, Object> version) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", Map.of("code", version.get("modelCode")));
        result.put("promptVersionId", version.get("promptVersionId"));
        if (version.get("knowledgeBaseId") != null) {
            result.put("knowledgeBaseId", version.get("knowledgeBaseId"));
        }
        List<Map<String, Object>> tools = repository.listVersionBindings(String.valueOf(version.get("id"))).stream()
                .filter(binding -> "MCP_TOOL_VERSION".equals(binding.get("resourceType")))
                .map(binding -> {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("toolId", binding.get("resourceId"));
                    if (binding.get("resourceVersion") != null) {
                        tool.put("toolVersionId", binding.get("resourceVersion"));
                    }
                    return Map.copyOf(tool);
                })
                .toList();
        result.put("tools", tools);
        return Map.copyOf(result);
    }

    private List<Map<String, Object>> validateVersionResources(
            String modelCode,
            String promptId,
            String promptVersionId,
            String knowledgeBaseId,
            List<Map<String, Object>> bindings
    ) {
        List<Map<String, Object>> issues = new ArrayList<>();
        List<Map<String, Object>> models = controlPlaneRepository.listDocuments("MODEL");
        boolean modelOk = models.stream().anyMatch(model -> modelCode.equals(model.get("code"))
                && !Boolean.FALSE.equals(model.get("enabled")));
        if (!modelOk) {
            issues.add(issue("MODEL", modelCode, "模型不存在或未启用。"));
        }
        List<Map<String, Object>> prompts = controlPlaneRepository.listDocuments("PROMPT");
        List<Map<String, Object>> promptVersions = controlPlaneRepository.listDocuments("PROMPT_VERSION");
        boolean promptOk = prompts.stream().anyMatch(prompt -> promptId.equals(prompt.get("id"))
                && promptVersionId.equals(prompt.get("publishedVersionId")))
                && promptVersions.stream().anyMatch(item -> promptVersionId.equals(item.get("id")));
        if (!promptOk) {
            issues.add(issue("PROMPT_VERSION", promptVersionId, "Prompt 版本未发布或不存在。"));
        }
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            Optional<Map<String, Object>> knowledgeBase = knowledgeRepository.findKnowledgeBase(knowledgeBaseId);
            if (knowledgeBase.isEmpty() || !"ACTIVE".equals(knowledgeBase.get().get("status"))) {
                issues.add(issue("KNOWLEDGE_BASE", knowledgeBaseId, "知识库不存在或未启用。"));
            }
        }
        List<Map<String, Object>> toolVersions = controlPlaneRepository.listMcpResources("MCP_TOOL_VERSION");
        List<Map<String, Object>> tools = controlPlaneRepository.listMcpResources("MCP_TOOL");
        for (Map<String, Object> binding : bindings) {
            String type = String.valueOf(binding.get("resourceType")).toUpperCase(Locale.ROOT);
            String resourceId = String.valueOf(binding.get("resourceId"));
            String resourceVersion = optionalString(binding, "resourceVersion");
            if (!BINDING_TYPES.contains(type)) {
                issues.add(issue(type, resourceId, "不支持的资源绑定类型。"));
                continue;
            }
            if ("MCP_TOOL_VERSION".equals(type)) {
                boolean ok = toolVersions.stream().anyMatch(item -> resourceId.equals(item.get("id")))
                        && tools.stream().anyMatch(tool -> resourceId.equals(tool.get("latestVersionId"))
                                && Boolean.TRUE.equals(tool.get("enabled")));
                if (!ok) {
                    issues.add(issue(type, resourceId, "MCP Tool 版本不存在、未启用或不是最新版本。"));
                }
            } else if ("MCP_SERVER".equals(type)) {
                boolean ok = controlPlaneRepository.listMcpResources("MCP_SERVER").stream()
                        .anyMatch(server -> resourceId.equals(server.get("id")) && Boolean.TRUE.equals(server.get("enabled")));
                if (!ok) {
                    issues.add(issue(type, resourceId, "MCP Server 不存在或未启用。"));
                }
            }
            if (resourceVersion != null && resourceVersion.isBlank() && "MCP_TOOL_VERSION".equals(type)) {
                issues.add(issue(type, resourceId, "Tool 绑定必须固定版本。"));
            }
        }
        return List.copyOf(issues);
    }

    private Map<String, Object> issue(String type, String resourceId, String message) {
        return Map.of("resourceType", type, "resourceId", resourceId, "message", message);
    }

    private Map<String, Object> application(String id) {
        return repository.findApplication(id).orElseThrow(() -> new IllegalArgumentException("应用不存在。"));
    }

    private Map<String, Object> version(String id) {
        return repository.findVersion(id).orElseThrow(() -> new IllegalArgumentException("版本不存在。"));
    }

    private Map<String, Object> safeApplication(Map<String, Object> value) {
        return immutable(value);
    }

    private Map<String, Object> safeVersion(Map<String, Object> value) {
        return immutable(value);
    }

    private Map<String, Object> safeBinding(Map<String, Object> value) {
        return immutable(value);
    }

    private Map<String, Object> safePublishRecord(Map<String, Object> value) {
        return immutable(value);
    }

    private Map<String, Object> safeApiKey(Map<String, Object> value) {
        Map<String, Object> safe = new LinkedHashMap<>(value);
        safe.remove("keyHash");
        return immutable(safe);
    }

    private Map<String, Object> immutable(Map<String, Object> value) {
        return Map.copyOf(value);
    }

    private void require(ControlPlanePrincipal principal, String permission) {
        if (principal == null || !principal.permissions().contains(permission)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }

    private void requireText(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空。");
        }
    }

    private String requiredString(Map<String, Object> input, String field) {
        Object value = input == null ? null : input.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空。");
        }
        return text.trim();
    }

    private String optionalString(Map<String, Object> input, String field) {
        Object value = input == null ? null : input.get(field);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        if (source == null) {
            return;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    private boolean matches(Map<String, Object> value, String keyword, String... fields) {
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        for (String field : fields) {
            Object item = value.get(field);
            if (item != null && String.valueOf(item).toLowerCase(Locale.ROOT).contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private ControlPlaneAudit audit(
            ControlPlanePrincipal principal,
            String action,
            String type,
            Object id,
            Map<String, Object> metadata
    ) {
        return new ControlPlaneAudit(UUID.randomUUID().toString(), principal.username(), action, type,
                String.valueOf(id), metadata, Instant.now());
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[KEY_BYTES];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    /** Immutable snapshot handed to the open Agent API after key authentication. */
    public record PublishedAppSnapshot(
            String applicationId,
            String appCode,
            String appDisplayName,
            Map<String, Object> version,
            List<Map<String, Object>> bindings
    ) {
        public PublishedAppSnapshot {
            version = Map.copyOf(version);
            bindings = List.copyOf(bindings);
        }

        public String modelCode() {
            return String.valueOf(version.get("modelCode"));
        }

        public String promptVersionId() {
            return String.valueOf(version.get("promptVersionId"));
        }

        public String knowledgeBaseId() {
            return version.get("knowledgeBaseId") == null ? null : String.valueOf(version.get("knowledgeBaseId"));
        }
    }
}
