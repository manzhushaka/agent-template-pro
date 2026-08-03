package com.manzhushaka.agent.controlplane;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Control-plane use cases with repository-backed RBAC, reference-only secrets, immutable Prompt
 * versions and append-only audits.
 */
public final class ControlPlaneService {
    public static final String RUNTIME_READ = "runtime:read";
    public static final String MODEL_READ = "model:read";
    public static final String MODEL_WRITE = "model:write";
    public static final String MODEL_TEST = "model:test";
    public static final String PROMPT_READ = "prompt:read";
    public static final String PROMPT_WRITE = "prompt:write";
    public static final String PROMPT_PUBLISH = "prompt:publish";
    public static final String SECRET_READ = "secret:read";
    public static final String SECRET_WRITE = "secret:write";
    public static final String AUDIT_READ = "audit:read";
    public static final String MCP_READ = "mcp:read";
    public static final String MCP_WRITE = "mcp:write";
    public static final String MCP_TEST = "mcp:test";
    public static final String MCP_SYNC = "mcp:sync";
    public static final String MCP_BIND = "mcp:bind";
    public static final String KNOWLEDGE_READ = "knowledge:read";
    public static final String KNOWLEDGE_WRITE = "knowledge:write";
    public static final String AGENT_READ = "agent:read";
    public static final String AGENT_WRITE = "agent:write";
    public static final String AGENT_PUBLISH = "agent:publish";
    public static final String AGENTAPP_READ = "agentapp:read";
    public static final String AGENTAPP_WRITE = "agentapp:write";
    public static final String AGENTAPP_PUBLISH = "agentapp:publish";
    public static final String APIKEY_READ = "apikey:read";
    public static final String APIKEY_WRITE = "apikey:write";
    public static final String TRACE_READ = "trace:read";
    public static final String EVAL_READ = "eval:read";
    public static final String EVAL_WRITE = "eval:write";
    public static final String EVAL_RUN = "eval:run";

    private static final Set<String> ALL_PERMISSIONS = Set.of(
            RUNTIME_READ, MODEL_READ, MODEL_WRITE, MODEL_TEST, PROMPT_READ, PROMPT_WRITE,
            PROMPT_PUBLISH, SECRET_READ, SECRET_WRITE, AUDIT_READ,
            MCP_READ, MCP_WRITE, MCP_TEST, MCP_SYNC, MCP_BIND, KNOWLEDGE_READ, KNOWLEDGE_WRITE,
            AGENT_READ, AGENT_WRITE, AGENT_PUBLISH, AGENTAPP_READ, AGENTAPP_WRITE, AGENTAPP_PUBLISH,
            APIKEY_READ, APIKEY_WRITE, TRACE_READ, EVAL_READ, EVAL_WRITE, EVAL_RUN,
            "workflow:read", "workflow:write", "workflow:run"
    );
    private static final Set<String> OPERATOR_PERMISSIONS = Set.of(
            RUNTIME_READ, MODEL_READ, MODEL_WRITE, MODEL_TEST, PROMPT_READ, PROMPT_WRITE,
            PROMPT_PUBLISH, SECRET_READ, MCP_READ, MCP_WRITE, MCP_TEST, MCP_SYNC, MCP_BIND,
            KNOWLEDGE_READ, KNOWLEDGE_WRITE, AGENT_READ, AGENT_WRITE, AGENT_PUBLISH,
            AGENTAPP_READ, AGENTAPP_WRITE, AGENTAPP_PUBLISH, APIKEY_READ, APIKEY_WRITE,
            TRACE_READ, EVAL_READ, EVAL_WRITE, EVAL_RUN,
            "workflow:read", "workflow:write", "workflow:run"
    );
    private static final Set<String> VIEWER_PERMISSIONS = Set.of(
            RUNTIME_READ, MODEL_READ, PROMPT_READ, SECRET_READ, AUDIT_READ, MCP_READ, KNOWLEDGE_READ,
            AGENT_READ, AGENTAPP_READ, APIKEY_READ, TRACE_READ, EVAL_READ, "workflow:read"
    );
    private static final Pattern ENV_REFERENCE = Pattern.compile("[A-Z][A-Z0-9_]{1,127}");
    private static final Pattern K8S_REFERENCE = Pattern.compile("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?/[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?#[A-Za-z0-9._-]{1,253}");
    private static final Pattern KMS_REFERENCE = Pattern.compile("[A-Za-z0-9:/._+=,@-]{3,500}");

    private final ControlPlaneRepository repository;
    private final SecretRefResolver secretRefResolver;
    private final ProviderConnectionTester connectionTester;
    private final Set<String> allowedProviderHosts;
    private final Duration connectionTimeout;
    private final Map<String, Map<String, Object>> providers = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> models = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> secretRefs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> prompts = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> promptVersions = new ConcurrentHashMap<>();

    public ControlPlaneService() {
        this(new InMemoryControlPlaneRepository());
    }

    public ControlPlaneService(ControlPlaneRepository repository) {
        this(repository, SecretRefResolver.unavailable(), ProviderConnectionTester.unavailable(), Set.of(), Duration.ofSeconds(5));
    }

    public ControlPlaneService(
            ControlPlaneRepository repository,
            SecretRefResolver secretRefResolver,
            ProviderConnectionTester connectionTester,
            Set<String> allowedProviderHosts,
            Duration connectionTimeout
    ) {
        this.repository = repository;
        this.secretRefResolver = secretRefResolver;
        this.connectionTester = connectionTester;
        this.allowedProviderHosts = allowedProviderHosts.stream()
                .map(host -> host.trim().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.connectionTimeout = connectionTimeout;
        refreshAll();
    }

    static Set<String> allPermissions() {
        return ALL_PERMISSIONS;
    }

    static Set<String> operatorPermissions() {
        return OPERATOR_PERMISSIONS;
    }

    static Set<String> viewerPermissions() {
        return VIEWER_PERMISSIONS;
    }

    public ControlPlanePrincipal principal(String username, String role) {
        String normalizedRole = role == null ? "UNASSIGNED" : role.trim().toUpperCase(Locale.ROOT);
        return new ControlPlanePrincipal(username, normalizedRole, repository.permissionsForRole(normalizedRole));
    }

    public void require(ControlPlanePrincipal principal, String permission) {
        if (principal == null || !principal.permissions().contains(permission)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }

    public List<Map<String, Object>> providers(ControlPlanePrincipal principal, String keyword) {
        require(principal, MODEL_READ);
        refresh("MODEL_PROVIDER", providers);
        return filtered(providers, keyword);
    }

    public Map<String, Object> saveProvider(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, MODEL_WRITE);
        refresh("MODEL_PROVIDER", providers);
        Map<String, Object> value = resource(providers, id, input, "code", "displayName", "providerType", "baseUrl", "enabled");
        value.put("id", id == null ? id() : id);
        validateEndpointIfPresent(string(value.get("baseUrl")));
        value.put("updatedAt", Instant.now().toString());
        persistWithAudit(principal, "MODEL_PROVIDER_SAVED", "MODEL_PROVIDER", value, Map.of("code", string(value.get("code"))));
        providers.put(string(value.get("id")), value);
        return immutableMap(value);
    }

    public List<Map<String, Object>> models(ControlPlanePrincipal principal, String keyword, String modelType) {
        require(principal, MODEL_READ);
        refresh("MODEL", models);
        refresh("SECRET_REF", secretRefs);
        return filtered(models, keyword).stream()
                .filter(item -> modelType == null || modelType.isBlank() || modelType.equals(item.get("modelType")))
                .map(this::safeModel)
                .toList();
    }

    public Map<String, Object> saveModel(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, MODEL_WRITE);
        refresh("MODEL", models);
        refresh("MODEL_PROVIDER", providers);
        refresh("SECRET_REF", secretRefs);
        Map<String, Object> value = resource(
                models, id, input, "providerId", "code", "displayName", "modelType", "modelName",
                "baseUrl", "secretRefId", "enabled", "timeoutMs"
        );
        value.put("id", id == null ? id() : id);
        validateEndpointIfPresent(effectiveEndpoint(value));
        validateModelReferences(value);
        value.remove("secretConfigured");
        value.put("updatedAt", Instant.now().toString());
        persistWithAudit(principal, "MODEL_SAVED", "MODEL", value, Map.of("code", string(value.get("code"))));
        models.put(string(value.get("id")), value);
        return safeModel(value);
    }

    public Map<String, Object> testModel(ControlPlanePrincipal principal, String id) {
        require(principal, MODEL_TEST);
        refreshAllModelResources();
        Map<String, Object> model = requireResource(models, id);
        String endpoint = effectiveEndpoint(model);
        validateEndpointIfPresent(endpoint);
        Optional<String> credential = resolveModelCredential(model);
        ProviderConnectionTestResult probe;
        if (endpoint.isBlank()) {
            probe = new ProviderConnectionTestResult("PROBE_UNAVAILABLE", "模型未配置受控 Provider HTTPS 地址。");
        } else if (credential.isEmpty()) {
            probe = new ProviderConnectionTestResult("PROBE_UNAVAILABLE", "模型未绑定当前部署可解析的 SecretRef。");
        } else {
            probe = connectionTester.test(URI.create(endpoint), credential.get(), connectionTimeout);
        }
        Map<String, Object> result = Map.of(
                "modelId", id,
                "status", probe.status(),
                "testedAt", Instant.now().toString(),
                "message", probe.message()
        );
        appendAudit(principal, "MODEL_CONNECTION_TESTED", "MODEL", id, Map.of("status", probe.status()));
        return result;
    }

    public List<Map<String, Object>> secretRefs(ControlPlanePrincipal principal) {
        require(principal, SECRET_READ);
        refresh("SECRET_REF", secretRefs);
        return secretRefs.values().stream().map(this::safeSecretRef).sorted(byUpdatedAt()).toList();
    }

    public Map<String, Object> saveSecretRef(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, SECRET_WRITE);
        refresh("SECRET_REF", secretRefs);
        Map<String, Object> value = resource(secretRefs, id, input, "name", "secretRefType", "reference");
        value.put("id", id == null ? id() : id);
        String referenceType = string(value.get("secretRefType")).toUpperCase(Locale.ROOT);
        String reference = string(value.get("reference"));
        validateSecretReference(referenceType, reference);
        value.put("secretRefType", referenceType);
        value.remove("configured");
        value.put("updatedAt", Instant.now().toString());
        persistWithAudit(principal, "SECRET_REF_SAVED", "SECRET_REF", value, Map.of("name", string(value.get("name"))));
        secretRefs.put(string(value.get("id")), value);
        return safeSecretRef(value);
    }

    public List<Map<String, Object>> prompts(ControlPlanePrincipal principal, String keyword) {
        require(principal, PROMPT_READ);
        refresh("PROMPT", prompts);
        return filtered(prompts, keyword);
    }

    public Map<String, Object> savePrompt(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        require(principal, PROMPT_WRITE);
        refresh("PROMPT", prompts);
        Map<String, Object> value = resource(prompts, id, input, "code", "displayName", "draftContent", "variableSchema");
        value.put("id", id == null ? id() : id);
        value.put("updatedAt", Instant.now().toString());
        persistWithAudit(principal, "PROMPT_DRAFT_SAVED", "PROMPT", value, Map.of("code", string(value.get("code"))));
        prompts.put(string(value.get("id")), value);
        return immutableMap(value);
    }

    public List<Map<String, Object>> promptVersions(ControlPlanePrincipal principal, String promptId) {
        require(principal, PROMPT_READ);
        refresh("PROMPT_VERSION", promptVersions);
        return promptVersions.values().stream()
                .filter(item -> promptId.equals(item.get("promptId")))
                .sorted(Comparator.comparingInt(item -> -((Number) item.get("version")).intValue()))
                .map(this::immutableMap)
                .toList();
    }

    public Map<String, Object> debugPrompt(ControlPlanePrincipal principal, String versionId, Map<String, Object> variables) {
        require(principal, PROMPT_READ);
        refresh("PROMPT_VERSION", promptVersions);
        Map<String, Object> version = requireResource(promptVersions, versionId);
        String content = string(version.get("content"));
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            content = content.replace("{{" + entry.getKey() + "}}", string(entry.getValue()));
        }
        return Map.of(
                "promptVersionId", versionId,
                "renderedPrompt", content,
                "traceId", "dbg_" + UUID.randomUUID(),
                "latencyMs", 0,
                "isolated", true
        );
    }

    public Map<String, Object> publishPrompt(ControlPlanePrincipal principal, String versionId) {
        return changePublishedVersion(principal, null, versionId, false);
    }

    public Map<String, Object> createVersion(ControlPlanePrincipal principal, String promptId) {
        require(principal, PROMPT_WRITE);
        refresh("PROMPT", prompts);
        Map<String, Object> prompt = requireResource(prompts, promptId);
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", id());
        version.put("promptId", promptId);
        version.put("content", string(prompt.get("draftContent")));
        if (prompt.get("variableSchema") != null) {
            version.put("variableSchema", immutableValue(prompt.get("variableSchema")));
        }
        version.put("createdAt", Instant.now().toString());
        ControlPlaneAudit audit = auditRecord(
                principal, "PROMPT_VERSION_CREATED", "PROMPT_VERSION", string(version.get("id")), Map.of("promptId", promptId)
        );
        Map<String, Object> persisted = repository.createPromptVersion(version, audit);
        promptVersions.put(string(persisted.get("id")), new LinkedHashMap<>(persisted));
        return immutableMap(persisted);
    }

    public Map<String, Object> rollbackPrompt(ControlPlanePrincipal principal, String promptId, String versionId) {
        return changePublishedVersion(principal, promptId, versionId, true);
    }

    public List<ControlPlaneAudit> audits(ControlPlanePrincipal principal) {
        require(principal, AUDIT_READ);
        return repository.listAudits().stream()
                .sorted(Comparator.comparing(ControlPlaneAudit::createdAt).reversed())
                .map(audit -> new ControlPlaneAudit(
                        audit.id(), audit.actor(), audit.action(), audit.resourceType(), audit.resourceId(),
                        immutableMap(audit.metadata()), audit.createdAt()
                ))
                .toList();
    }

    private Map<String, Object> changePublishedVersion(
            ControlPlanePrincipal principal,
            String expectedPromptId,
            String versionId,
            boolean rollback
    ) {
        require(principal, PROMPT_PUBLISH);
        refresh("PROMPT_VERSION", promptVersions);
        refresh("PROMPT", prompts);
        Map<String, Object> version = requireResource(promptVersions, versionId);
        String promptId = string(version.get("promptId"));
        if (expectedPromptId != null && !expectedPromptId.equals(promptId)) {
            throw new IllegalArgumentException("Prompt 版本不属于目标 Prompt。");
        }
        String action = rollback ? "PROMPT_VERSION_ROLLED_BACK" : "PROMPT_VERSION_PUBLISHED";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("promptId", promptId);
        metadata.put("targetVersionId", versionId);
        Map<String, Object> updatedPrompt = repository.publishPrompt(
                promptId, versionId, auditRecord(principal, action, "PROMPT_VERSION", versionId, metadata)
        );
        prompts.put(promptId, new LinkedHashMap<>(updatedPrompt));
        return immutableMap(version);
    }

    private List<Map<String, Object>> filtered(Map<String, Map<String, Object>> source, String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return source.values().stream()
                .filter(item -> needle.isEmpty() || item.values().stream()
                        .anyMatch(value -> string(value).toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(byUpdatedAt())
                .map(this::immutableMap)
                .toList();
    }

    private Comparator<Map<String, Object>> byUpdatedAt() {
        return Comparator.comparing(item -> string(item.get("updatedAt")), Comparator.reverseOrder());
    }

    private Map<String, Object> resource(
            Map<String, Map<String, Object>> source,
            String id,
            Map<String, Object> input,
            String... fields
    ) {
        Map<String, Object> value = id == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(requireResource(source, id));
        for (String field : fields) {
            if (input.containsKey(field)) {
                value.put(field, immutableValue(input.get(field)));
            }
        }
        return value;
    }

    private Map<String, Object> requireResource(Map<String, Map<String, Object>> source, String id) {
        Map<String, Object> resource = source.get(id);
        if (resource == null) {
            throw new IllegalArgumentException("资源不存在。");
        }
        return resource;
    }

    private Map<String, Object> safeModel(Map<String, Object> source) {
        Map<String, Object> response = new LinkedHashMap<>(source);
        response.remove("secretRefId");
        response.put("secretConfigured", resolveModelCredential(source).isPresent());
        return immutableMap(response);
    }

    private Map<String, Object> safeSecretRef(Map<String, Object> source) {
        Map<String, Object> response = new LinkedHashMap<>(source);
        String referenceType = string(source.get("secretRefType"));
        String reference = string(source.get("reference"));
        response.remove("reference");
        response.remove("referenceLocator");
        response.put("configured", secretRefResolver.resolve(referenceType, reference).isPresent());
        return immutableMap(response);
    }

    private void validateModelReferences(Map<String, Object> model) {
        String providerId = string(model.get("providerId"));
        if (!providerId.isBlank()) {
            requireResource(providers, providerId);
        }
        String secretRefId = string(model.get("secretRefId"));
        if (!secretRefId.isBlank()) {
            requireResource(secretRefs, secretRefId);
        }
    }

    private Optional<String> resolveModelCredential(Map<String, Object> model) {
        String secretRefId = string(model.get("secretRefId"));
        if (secretRefId.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> secret = secretRefs.get(secretRefId);
        if (secret == null) {
            return Optional.empty();
        }
        return secretRefResolver.resolve(string(secret.get("secretRefType")), string(secret.get("reference")));
    }

    private String effectiveEndpoint(Map<String, Object> model) {
        String endpoint = string(model.get("baseUrl"));
        if (!endpoint.isBlank()) {
            return endpoint;
        }
        String providerId = string(model.get("providerId"));
        Map<String, Object> provider = providers.get(providerId);
        return provider == null ? "" : string(provider.get("baseUrl"));
    }

    private void validateSecretReference(String referenceType, String reference) {
        boolean valid = switch (referenceType) {
            case "ENV" -> ENV_REFERENCE.matcher(reference).matches();
            case "K8S" -> K8S_REFERENCE.matcher(reference).matches();
            case "KMS" -> KMS_REFERENCE.matcher(reference).matches();
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("SecretRef 类型或引用格式无效。");
        }
    }

    private void validateEndpointIfPresent(String endpoint) {
        if (endpoint.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("模型地址不在受控 HTTPS 白名单内。");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowedHost = allowedProviderHosts.stream()
                .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null
                || host.isBlank() || !allowedHost) {
            throw new IllegalArgumentException("模型地址不在受控 HTTPS 白名单内。");
        }
    }

    private void persistWithAudit(
            ControlPlanePrincipal principal,
            String action,
            String resourceType,
            Map<String, Object> value,
            Map<String, Object> metadata
    ) {
        repository.saveDocumentWithAudit(
                resourceType,
                immutableMap(value),
                auditRecord(principal, action, resourceType, string(value.get("id")), metadata)
        );
    }

    private void appendAudit(
            ControlPlanePrincipal principal,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> metadata
    ) {
        repository.appendAudit(auditRecord(principal, action, resourceType, resourceId, metadata));
    }

    private ControlPlaneAudit auditRecord(
            ControlPlanePrincipal principal,
            String action,
            String resourceType,
            String resourceId,
            Map<String, Object> metadata
    ) {
        return new ControlPlaneAudit(
                id(), principal.username(), action, resourceType, resourceId, immutableMap(metadata), Instant.now()
        );
    }

    private void refreshAll() {
        refresh("MODEL_PROVIDER", providers);
        refresh("MODEL", models);
        refresh("SECRET_REF", secretRefs);
        refresh("PROMPT", prompts);
        refresh("PROMPT_VERSION", promptVersions);
    }

    private void refreshAllModelResources() {
        refresh("MODEL_PROVIDER", providers);
        refresh("MODEL", models);
        refresh("SECRET_REF", secretRefs);
    }

    private synchronized void refresh(String type, Map<String, Map<String, Object>> target) {
        Map<String, Map<String, Object>> loaded = new LinkedHashMap<>();
        repository.listDocuments(type).forEach(document -> loaded.put(
                string(document.get("id")), new LinkedHashMap<>(document)
        ));
        target.clear();
        target.putAll(loaded);
    }

    @SuppressWarnings("unchecked")
    private Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutableValue(item)));
            return Map.copyOf(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return List.copyOf(copy);
        }
        return value;
    }

    private Map<String, Object> immutableMap(Map<String, Object> source) {
        return (Map<String, Object>) immutableValue(source);
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String id() {
        return UUID.randomUUID().toString();
    }
}
