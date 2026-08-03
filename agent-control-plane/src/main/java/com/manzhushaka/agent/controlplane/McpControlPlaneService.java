package com.manzhushaka.agent.controlplane;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * MCP lifecycle and tool catalog management. This service has no runtime execution dependency:
 * write tools are catalogued here but cannot be called by Console Debug or directly from this API.
 */
public final class McpControlPlaneService {
    private static final String SERVER = "MCP_SERVER";
    private static final String TOOL = "MCP_TOOL";
    private static final String TOOL_VERSION = "MCP_TOOL_VERSION";
    private static final String BINDING = "AGENT_TOOL_BINDING";
    private static final Set<String> HTTP_TRANSPORTS = Set.of("SSE", "STREAMABLE_HTTP");
    private static final Set<String> ALL_TRANSPORTS = Set.of("SSE", "STREAMABLE_HTTP", "STDIO");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Duration SYNC_LEASE_DURATION = Duration.ofMinutes(15);

    private final ControlPlaneService access;
    private final ControlPlaneRepository repository;
    private final SecretRefResolver secretResolver;
    private final McpTransportClient transportClient;
    private final Set<String> allowedHosts;
    private final Set<String> allowedStdioCommands;
    private final boolean stdioEnabled;
    private final Duration timeout;

    public McpControlPlaneService(
            ControlPlaneService access,
            ControlPlaneRepository repository,
            SecretRefResolver secretResolver,
            McpTransportClient transportClient,
            Set<String> allowedHosts,
            Set<String> allowedStdioCommands,
            boolean stdioEnabled,
            Duration timeout
    ) {
        this.access = access;
        this.repository = repository;
        this.secretResolver = secretResolver;
        this.transportClient = transportClient;
        this.allowedHosts = normalize(allowedHosts);
        this.allowedStdioCommands = Set.copyOf(allowedStdioCommands);
        this.stdioEnabled = stdioEnabled;
        this.timeout = timeout;
    }

    public List<Map<String, Object>> servers(ControlPlanePrincipal principal, String keyword) {
        access.require(principal, ControlPlaneService.MCP_READ);
        return filter(safeServers(), keyword);
    }

    public Map<String, Object> saveServer(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        access.require(principal, ControlPlaneService.MCP_WRITE);
        Map<String, Object> value = current(SERVER, id);
        copy(input, value, "code", "displayName", "transport", "endpoint", "command", "arguments", "secretRefId", "enabled", "timeoutMs");
        value.put("id", id == null ? id() : id);
        String transport = string(value.get("transport")).toUpperCase(Locale.ROOT);
        validateTransport(value, transport);
        value.put("transport", transport);
        validateSecretRef(value);
        value.put("enabled", Boolean.TRUE.equals(value.get("enabled")));
        if (id != null && !Boolean.TRUE.equals(value.get("enabled"))) {
            List<Map<String, Object>> activeReferences = documents(TOOL).stream()
                    .filter(tool -> id.equals(tool.get("serverId")))
                    .flatMap(tool -> references(string(tool.get("id"))).stream())
                    .toList();
            if (!activeReferences.isEmpty()) {
                throw new McpBindingConflictException("MCP Server 仍被 Agent 绑定引用，不能停用。", activeReferences);
            }
        }
        value.put("updatedAt", Instant.now().toString());
        persist(principal, "MCP_SERVER_SAVED", SERVER, value, Map.of("code", string(value.get("code"))));
        return safeServer(value);
    }

    public Map<String, Object> testServer(ControlPlanePrincipal principal, String serverId) {
        access.require(principal, ControlPlaneService.MCP_TEST);
        Map<String, Object> server = require(SERVER, serverId);
        McpTransportResult probe;
        try {
            probe = transportClient.test(connection(server), timeout(server));
        } catch (RuntimeException exception) {
            probe = new McpTransportResult("CONNECTION_FAILED", "MCP transport 连接失败。");
        }
        Map<String, Object> updated = new LinkedHashMap<>(server);
        updated.put("healthStatus", probe.status());
        updated.put("lastTestedAt", Instant.now().toString());
        updated.put("updatedAt", Instant.now().toString());
        persist(principal, "MCP_SERVER_TESTED", SERVER, updated, Map.of("status", probe.status()));
        return Map.of("serverId", serverId, "status", probe.status(), "message", probe.message(), "testedAt", updated.get("lastTestedAt"));
    }

    public Map<String, Object> syncServer(ControlPlanePrincipal principal, String serverId) {
        access.require(principal, ControlPlaneService.MCP_SYNC);
        Map<String, Object> server = require(SERVER, serverId);
        if (!Boolean.TRUE.equals(server.get("enabled"))) {
            throw new IllegalStateException("已停用的 MCP Server 不可同步。");
        }
        String syncId = id();
        Instant startedAt = Instant.now();
        if (!repository.claimMcpSync(syncId, serverId, startedAt, startedAt.plus(SYNC_LEASE_DURATION))) {
            throw new McpSyncConflictException();
        }
        try {
        List<McpDiscoveredTool> discovered;
        try {
            discovered = transportClient.discover(connection(server), timeout(server));
            Set<String> names = new HashSet<>();
            for (McpDiscoveredTool discoveredTool : discovered) {
                validateDiscoveredTool(discoveredTool);
                if (!names.add(discoveredTool.name())) {
                    throw new IllegalArgumentException("MCP Tool discovery 返回了重复名称。");
                }
            }
        } catch (RuntimeException exception) {
            appendAudit(principal, "MCP_SERVER_SYNC_FAILED", SERVER, serverId, Map.of("reason", "REMOTE_DISCOVERY_FAILED"));
            throw new McpTransportException("MCP Tool discovery 失败。");
        }
        int createdVersions = 0;
        List<Map<String, Object>> existingTools = new ArrayList<>(documents(TOOL));
        List<Map<String, Object>> existingVersions = new ArrayList<>(documents(TOOL_VERSION));
        Set<String> discoveredNames = new HashSet<>();
        for (McpDiscoveredTool discoveredTool : discovered) {
            discoveredNames.add(discoveredTool.name());
            Map<String, Object> tool = existingTools.stream()
                    .filter(item -> serverId.equals(item.get("serverId")) && discoveredTool.name().equals(item.get("name")))
                    .findFirst().map(LinkedHashMap::new).orElseGet(LinkedHashMap::new);
            boolean newTool = tool.isEmpty();
            if (newTool) {
                tool.put("id", id());
                tool.put("serverId", serverId);
                tool.put("name", discoveredTool.name());
                tool.put("enabled", true);
            }
            tool.remove("retiredAt");
            String toolId = string(tool.get("id"));
            String digest = digest(discoveredTool);
            Map<String, Object> version = existingVersions.stream()
                    .filter(item -> toolId.equals(item.get("toolId")) && digest.equals(item.get("schemaDigest")))
                    .findFirst().map(LinkedHashMap::new).orElse(null);
            if (version == null) {
                version = new LinkedHashMap<>();
                version.put("id", id());
                version.put("toolId", toolId);
                version.put("serverId", serverId);
                version.put("toolName", discoveredTool.name());
                version.put("schemaDigest", digest);
                version.put("inputSchema", immutable(discoveredTool.inputSchema()));
                version.put("outputSchema", immutable(discoveredTool.outputSchema()));
                version.put("description", discoveredTool.description());
                version.put("riskLevel", normalizedRisk(discoveredTool.riskLevel(), discoveredTool.writeTool()));
                version.put("writeTool", discoveredTool.writeTool());
                version.put("createdAt", Instant.now().toString());
                persist(principal, "MCP_TOOL_VERSION_DISCOVERED", TOOL_VERSION, version,
                        Map.of("toolId", toolId, "schemaDigest", digest));
                existingVersions.add(version);
                createdVersions++;
            }
            tool.put("latestVersionId", version.get("id"));
            tool.put("displayName", discoveredTool.name());
            tool.put("riskLevel", version.get("riskLevel"));
            tool.put("writeTool", version.get("writeTool"));
            tool.put("updatedAt", Instant.now().toString());
            persist(principal, newTool ? "MCP_TOOL_DISCOVERED" : "MCP_TOOL_SYNCED", TOOL, tool,
                    Map.of("serverId", serverId, "toolName", discoveredTool.name()));
            existingTools.removeIf(item -> string(item.get("id")).equals(tool.get("id")));
            existingTools.add(tool);
        }
        for (Map<String, Object> existingTool : existingTools) {
            if (!serverId.equals(existingTool.get("serverId")) || discoveredNames.contains(existingTool.get("name"))
                    || existingTool.get("retiredAt") != null) {
                continue;
            }
            Map<String, Object> retired = new LinkedHashMap<>(existingTool);
            retired.put("retiredAt", Instant.now().toString());
            retired.put("updatedAt", Instant.now().toString());
            persist(principal, "MCP_TOOL_RETIRED", TOOL, retired, Map.of("serverId", serverId));
        }
        Map<String, Object> updatedServer = new LinkedHashMap<>(server);
        updatedServer.put("healthStatus", "CONNECTED");
        updatedServer.put("lastSyncedAt", Instant.now().toString());
        updatedServer.put("updatedAt", Instant.now().toString());
        persist(principal, "MCP_SERVER_SYNCED", SERVER, updatedServer,
                Map.of("toolCount", discovered.size(), "createdVersionCount", createdVersions));
        repository.finishMcpSync(syncId, "SUCCEEDED", discovered.size(), createdVersions, null, Instant.now());
        return Map.of("serverId", serverId, "toolCount", discovered.size(), "createdVersionCount", createdVersions,
                "syncedAt", updatedServer.get("lastSyncedAt"));
        } catch (RuntimeException exception) {
            repository.finishMcpSync(syncId, "FAILED", 0, 0, "MCP_SYNC_FAILED", Instant.now());
            throw exception;
        }
    }

    public List<Map<String, Object>> tools(ControlPlanePrincipal principal, String keyword, String serverId) {
        access.require(principal, ControlPlaneService.MCP_READ);
        return filter(documents(TOOL).stream().filter(tool -> serverId == null || serverId.isBlank() || serverId.equals(tool.get("serverId")))
                .map(this::safeTool).toList(), keyword);
    }

    public List<Map<String, Object>> toolVersions(ControlPlanePrincipal principal, String toolId) {
        access.require(principal, ControlPlaneService.MCP_READ);
        return documents(TOOL_VERSION).stream().filter(version -> toolId.equals(version.get("toolId")))
                .sorted(Comparator.comparing(item -> string(item.get("createdAt")), Comparator.reverseOrder()))
                .map(McpControlPlaneService::immutableMap).toList();
    }

    public Map<String, Object> setToolEnabled(ControlPlanePrincipal principal, String toolId, boolean enabled) {
        access.require(principal, ControlPlaneService.MCP_WRITE);
        Map<String, Object> tool = require(TOOL, toolId);
        if (!enabled && !references(toolId).isEmpty()) {
            throw new McpToolReferenceConflictException(toolId, references(toolId));
        }
        if (enabled && tool.get("retiredAt") != null) {
            throw new IllegalStateException("已退役的 MCP Tool 必须先通过同步重新发现，不能直接启用。");
        }
        Map<String, Object> updated = new LinkedHashMap<>(tool);
        updated.put("enabled", enabled);
        updated.put("updatedAt", Instant.now().toString());
        persist(principal, enabled ? "MCP_TOOL_ENABLED" : "MCP_TOOL_DISABLED", TOOL, updated, Map.of());
        return safeTool(updated);
    }

    public Map<String, Object> bindAgent(ControlPlanePrincipal principal, String id, Map<String, Object> input) {
        access.require(principal, ControlPlaneService.MCP_BIND);
        Map<String, Object> value = current(BINDING, id);
        copy(input, value, "agentCode", "toolVersionId", "enabled");
        value.put("id", id == null ? id() : id);
        Map<String, Object> version = require(TOOL_VERSION, string(value.get("toolVersionId")));
        Map<String, Object> tool = require(TOOL, string(version.get("toolId")));
        Map<String, Object> server = require(SERVER, string(tool.get("serverId")));
        if (!Boolean.TRUE.equals(server.get("enabled")) || !Boolean.TRUE.equals(tool.get("enabled"))
                || tool.get("retiredAt") != null) {
            throw new IllegalStateException("不能绑定已停用或已退役的 MCP Tool。");
        }
        List<Map<String, Object>> conflicting = documents(BINDING).stream()
                .filter(binding -> !string(binding.get("id")).equals(string(value.get("id"))))
                .filter(binding -> string(value.get("agentCode")).equals(binding.get("agentCode")))
                .filter(binding -> string(value.get("toolVersionId")).equals(binding.get("toolVersionId")))
                .filter(binding -> !Boolean.FALSE.equals(binding.get("enabled")))
                .map(McpControlPlaneService::immutableMap)
                .toList();
        if (!conflicting.isEmpty()) {
            throw new McpBindingConflictException("Agent 已绑定该 MCP Tool 版本。", conflicting);
        }
        value.put("toolId", tool.get("id"));
        value.put("enabled", !Boolean.FALSE.equals(value.get("enabled")));
        value.put("updatedAt", Instant.now().toString());
        try {
            persist(principal, "AGENT_MCP_TOOL_BOUND", BINDING, value,
                    Map.of("agentCode", string(value.get("agentCode")), "toolVersionId", string(value.get("toolVersionId"))));
        } catch (McpDuplicateResourceException exception) {
            throw new McpBindingConflictException("Agent 已绑定该 MCP Tool 版本。", references(string(tool.get("id"))));
        }
        return immutableMap(value);
    }

    public List<Map<String, Object>> references(ControlPlanePrincipal principal, String toolId) {
        access.require(principal, ControlPlaneService.MCP_READ);
        return references(toolId);
    }

    /** Console debug is intentionally read-only: write tools must enter Runtime via a confirmed action. */
    public Map<String, Object> debugTool(ControlPlanePrincipal principal, String toolId, Map<String, Object> input) {
        access.require(principal, ControlPlaneService.MCP_TEST);
        Map<String, Object> tool = require(TOOL, toolId);
        if (Boolean.TRUE.equals(tool.get("writeTool"))) {
            appendAudit(principal, "MCP_TOOL_DEBUG_DENIED", TOOL, toolId, Map.of("reason", "WRITE_TOOL_REQUIRES_RUNTIME_CONFIRMATION"));
            throw new McpWriteToolConfirmationRequiredException();
        }
        if (!Boolean.TRUE.equals(tool.get("enabled")) || tool.get("retiredAt") != null) {
            throw new IllegalStateException("已停用或已退役的 MCP Tool 不可 Debug。");
        }
        appendAudit(principal, "MCP_TOOL_DEBUG_REQUESTED", TOOL, toolId, Map.of("inputFieldCount", input == null ? 0 : input.size()));
        return Map.of("toolId", toolId, "status", "DEBUG_NOT_EXECUTED", "traceId", "dbg_" + UUID.randomUUID(), "isolated", true);
    }

    /**
     * Runtime-facing tool snapshot for Workflow MCP_TOOL nodes. Requires no admin principal but
     * still resolves through the same enabled/retired checks and controlled connection builder.
     */
    public McpRuntimeToolSnapshot runtimeTool(String toolVersionId) {
        Map<String, Object> version = require(TOOL_VERSION, toolVersionId);
        Map<String, Object> tool = require(TOOL, string(version.get("toolId")));
        if (!Boolean.TRUE.equals(tool.get("enabled")) || tool.get("retiredAt") != null) {
            throw new IllegalStateException("MCP Tool 已停用或已退役。");
        }
        Map<String, Object> server = require(SERVER, string(tool.get("serverId")));
        if (!Boolean.TRUE.equals(server.get("enabled"))) {
            throw new IllegalStateException("MCP Server 已停用。");
        }
        return new McpRuntimeToolSnapshot(
                string(tool.get("name")),
                Boolean.TRUE.equals(tool.get("writeTool")),
                connection(server),
                string(tool.get("id")),
                string(version.get("id"))
        );
    }

    private void validateTransport(Map<String, Object> value, String transport) {
        if (!ALL_TRANSPORTS.contains(transport)) {
            throw new IllegalArgumentException("MCP transport 仅支持 SSE、STREAMABLE_HTTP 或受控 STDIO。");
        }
        if (HTTP_TRANSPORTS.contains(transport)) {
            validateEndpoint(string(value.get("endpoint")));
            value.remove("command");
            value.remove("arguments");
        } else {
            if (!stdioEnabled || !allowedStdioCommands.contains(string(value.get("command")))) {
                throw new IllegalArgumentException("当前部署不允许该 STDIO MCP 命令。");
            }
            List<?> args = value.get("arguments") instanceof List<?> list ? list : List.of();
            if (args.stream().anyMatch(arg -> String.valueOf(arg).contains("\u0000"))) {
                throw new IllegalArgumentException("STDIO 参数无效。");
            }
            value.put("arguments", List.copyOf(args.stream().map(String::valueOf).toList()));
            value.remove("endpoint");
        }
    }

    private void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MCP 地址不在受控 HTTPS 白名单内。");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedHosts.stream().anyMatch(candidate -> host.equals(candidate) || host.endsWith("." + candidate));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                || uri.getPort() != -1 || host.isBlank() || !allowed) {
            throw new IllegalArgumentException("MCP 地址不在受控 HTTPS 白名单内。");
        }
    }

    private void validateSecretRef(Map<String, Object> server) {
        String secretRefId = string(server.get("secretRefId"));
        if (!secretRefId.isBlank() && documents("SECRET_REF").stream().noneMatch(secret -> secretRefId.equals(secret.get("id")))) {
            throw new IllegalArgumentException("MCP Server 绑定的 SecretRef 不存在。");
        }
    }

    private McpServerConnection connection(Map<String, Object> server) {
        String secretRefId = string(server.get("secretRefId"));
        Optional<String> credential = documents("SECRET_REF").stream().filter(secret -> secretRefId.equals(secret.get("id"))).findFirst()
                .flatMap(secret -> secretResolver.resolve(string(secret.get("secretRefType")), string(secret.get("reference"))));
        return new McpServerConnection(string(server.get("id")), string(server.get("transport")), string(server.get("endpoint")),
                string(server.get("command")), list(server.get("arguments")), credential.orElse(""));
    }

    private Duration timeout(Map<String, Object> server) {
        Object configured = server.get("timeoutMs");
        long value = configured instanceof Number number ? number.longValue() : timeout.toMillis();
        return Duration.ofMillis(Math.min(60_000L, Math.max(250L, value)));
    }

    private void validateDiscoveredTool(McpDiscoveredTool tool) {
        if (tool.name() == null || !tool.name().matches("[A-Za-z0-9_.-]{1,120}")) {
            throw new IllegalArgumentException("MCP Tool 名称无效。");
        }
        normalizedRisk(tool.riskLevel(), tool.writeTool());
    }

    private String normalizedRisk(String risk, boolean writeTool) {
        String value = risk == null || risk.isBlank() ? (writeTool ? "HIGH" : "LOW") : risk.toUpperCase(Locale.ROOT);
        if (!RISK_LEVELS.contains(value)) {
            throw new IllegalArgumentException("MCP Tool 风险等级无效。");
        }
        return value;
    }

    private String digest(McpDiscoveredTool tool) {
        String risk = normalizedRisk(tool.riskLevel(), tool.writeTool());
        String value = canonical(Map.of(
                "name", tool.name(),
                "description", tool.description() == null ? "" : tool.description(),
                "inputSchema", tool.inputSchema(),
                "outputSchema", tool.outputSchema(),
                "riskLevel", risk,
                "writeTool", tool.writeTool()
        ));
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte valueByte : bytes) hex.append(String.format("%02x", valueByte));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 MCP Tool Schema 摘要。", exception);
        }
    }

    private List<Map<String, Object>> references(String toolId) {
        return documents(BINDING).stream().filter(binding -> toolId.equals(binding.get("toolId")) && !Boolean.FALSE.equals(binding.get("enabled")))
                .map(McpControlPlaneService::immutableMap).toList();
    }

    private List<Map<String, Object>> safeServers() { return documents(SERVER).stream().map(this::safeServer).toList(); }
    private Map<String, Object> safeServer(Map<String, Object> value) {
        Map<String, Object> safe = new LinkedHashMap<>(value);
        safe.remove("secretRefId");
        safe.remove("command");
        safe.remove("arguments");
        safe.put("secretConfigured", !string(value.get("secretRefId")).isBlank() && !connection(value).credential().isBlank());
        return immutableMap(safe);
    }
    private Map<String, Object> safeTool(Map<String, Object> value) { return immutableMap(value); }
    private Map<String, Object> require(String type, String id) {
        return documents(type).stream().filter(item -> id.equals(item.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("资源不存在。"));
    }
    private Map<String, Object> current(String type, String id) { return id == null ? new LinkedHashMap<>() : new LinkedHashMap<>(require(type, id)); }
    private List<Map<String, Object>> documents(String type) {
        List<Map<String, Object>> values = switch (type) {
            case SERVER, TOOL, TOOL_VERSION, BINDING -> repository.listMcpResources(type);
            default -> repository.listDocuments(type);
        };
        return values.stream().map(document -> (Map<String, Object>) new LinkedHashMap<>(document)).toList();
    }
    private void persist(ControlPlanePrincipal principal, String action, String type, Map<String, Object> value, Map<String, Object> metadata) {
        ControlPlaneAudit audit = audit(principal, action, type, string(value.get("id")), metadata);
        if (SERVER.equals(type) || TOOL.equals(type) || TOOL_VERSION.equals(type) || BINDING.equals(type)) {
            repository.saveMcpResourceWithAudit(type, immutableMap(value), audit);
            return;
        }
        repository.saveDocumentWithAudit(type, immutableMap(value), audit);
    }
    private void appendAudit(ControlPlanePrincipal principal, String action, String type, String resourceId, Map<String, Object> metadata) { repository.appendAudit(audit(principal, action, type, resourceId, metadata)); }
    private ControlPlaneAudit audit(ControlPlanePrincipal principal, String action, String type, String id, Map<String, Object> metadata) { return new ControlPlaneAudit(id(), principal.username(), action, type, id, immutableMap(metadata), Instant.now()); }
    private List<Map<String, Object>> filter(List<Map<String, Object>> values, String keyword) {
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return values.stream().filter(item -> needle.isEmpty() || item.values().stream().anyMatch(value -> string(value).toLowerCase(Locale.ROOT).contains(needle)))
                .sorted(Comparator.comparing(item -> string(item.get("updatedAt")), Comparator.reverseOrder())).toList();
    }
    private static Set<String> normalize(Set<String> values) { return values.stream().map(value -> value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet()); }
    private static void copy(Map<String, Object> source, Map<String, Object> target, String... fields) { for (String field : fields) if (source.containsKey(field)) target.put(field, immutable(source.get(field))); }
    @SuppressWarnings("unchecked") private static Map<String, Object> immutableMap(Map<String, Object> map) { return (Map<String, Object>) immutable((Object) map); }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), immutable(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return java.util.Collections.unmodifiableList(new ArrayList<>(list.stream().map(McpControlPlaneService::immutable).toList()));
        }
        return value;
    }
    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
            return sorted.entrySet().stream()
                    .map(entry -> "K" + entry.getKey().length() + ":" + entry.getKey() + canonical(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("", "M" + sorted.size() + "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(McpControlPlaneService::canonical)
                    .collect(java.util.stream.Collectors.joining("", "L" + list.size() + "[", "]"));
        }
        if (value == null) return "Z";
        if (value instanceof String text) return "S" + text.length() + ":" + text;
        if (value instanceof Boolean bool) return bool ? "B1" : "B0";
        if (value instanceof Number number) return "N" + number.getClass().getName() + ":" + number;
        return "O" + value.getClass().getName() + ":" + value;
    }
    private static List<String> list(Object value) { return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of(); }
    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String id() { return UUID.randomUUID().toString(); }
}
