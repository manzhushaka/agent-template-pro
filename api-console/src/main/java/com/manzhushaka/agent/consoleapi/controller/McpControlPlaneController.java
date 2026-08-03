package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.McpBindingConflictException;
import com.manzhushaka.agent.controlplane.McpControlPlaneService;
import com.manzhushaka.agent.controlplane.McpToolReferenceConflictException;
import com.manzhushaka.agent.controlplane.McpSyncConflictException;
import com.manzhushaka.agent.controlplane.McpTransportException;
import com.manzhushaka.agent.controlplane.McpWriteToolConfirmationRequiredException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/console/v1")
public class McpControlPlaneController {
    private final ConsoleAuthenticationService authenticationService;
    private final McpControlPlaneService mcpService;

    public McpControlPlaneController(ConsoleAuthenticationService authenticationService, McpControlPlaneService mcpService) {
        this.authenticationService = authenticationService;
        this.mcpService = mcpService;
    }

    @GetMapping("/mcp-servers")
    public PageResponse<Map<String, Object>> servers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(mcpService.servers(principal(authorization), keyword), page, size);
    }

    @PostMapping("/mcp-servers")
    public Map<String, Object> createServer(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return mcpService.saveServer(principal(authorization), null, request);
    }

    @PutMapping("/mcp-servers/{id}")
    public Map<String, Object> updateServer(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return mcpService.saveServer(principal(authorization), id, request);
    }

    @PostMapping("/mcp-servers/{id}:test")
    public Map<String, Object> testServer(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.testServer(principal(authorization), id);
    }

    @PostMapping("/mcp-servers/{id}:sync")
    public Map<String, Object> syncServer(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.syncServer(principal(authorization), id);
    }

    @GetMapping("/mcp-servers/{id}/tools")
    public List<Map<String, Object>> serverTools(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.tools(principal(authorization), null, id);
    }

    @GetMapping("/mcp-tools")
    public PageResponse<Map<String, Object>> tools(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String serverId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(mcpService.tools(principal(authorization), keyword, serverId), page, size);
    }

    @GetMapping("/mcp-tools/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.toolVersions(principal(authorization), id);
    }

    @PostMapping("/mcp-tools/{id}:enable")
    public Map<String, Object> enable(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.setToolEnabled(principal(authorization), id, true);
    }

    @PostMapping("/mcp-tools/{id}:disable")
    public Map<String, Object> disable(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.setToolEnabled(principal(authorization), id, false);
    }

    @PostMapping("/mcp-tools/{id}:debug")
    public Map<String, Object> debug(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> input
    ) {
        return mcpService.debugTool(principal(authorization), id, input == null ? Map.of() : input);
    }

    @GetMapping("/mcp-tools/{id}/references")
    public List<Map<String, Object>> references(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return mcpService.references(principal(authorization), id);
    }

    @PostMapping("/agent-tool-bindings")
    public Map<String, Object> bindAgent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return mcpService.bindAgent(principal(authorization), null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ControlPlaneAccessDeniedException.class)
    public ResponseEntity<ConsoleErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "CONSOLE_PERMISSION_DENIED", "当前管理员没有执行此操作的权限。");
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> unauthenticated(ConsoleAuthenticationException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.reason().code(), exception.reason().message());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(McpWriteToolConfirmationRequiredException.class)
    public ResponseEntity<ConsoleErrorResponse> confirmationRequired() {
        return error(HttpStatus.CONFLICT, "MCP_WRITE_TOOL_CONFIRMATION_REQUIRED", "写类型 MCP Tool 必须经 Runtime 任务和二次确认执行。");
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(McpToolReferenceConflictException.class)
    public ResponseEntity<Map<String, Object>> referenced(McpToolReferenceConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "MCP_TOOL_REFERENCED", "message", exception.getMessage(), "toolId", exception.toolId(), "references", exception.references()
        ));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(McpSyncConflictException.class)
    public ResponseEntity<ConsoleErrorResponse> syncConflict(McpSyncConflictException exception) {
        return error(HttpStatus.CONFLICT, "MCP_SYNC_IN_PROGRESS", exception.getMessage());
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(McpBindingConflictException.class)
    public ResponseEntity<Map<String, Object>> bindingConflict(McpBindingConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(Map.of(
                "code", "MCP_BINDING_CONFLICT", "message", exception.getMessage(), "references", exception.references()
        ));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(McpTransportException.class)
    public ResponseEntity<ConsoleErrorResponse> transportFailure() {
        return error(HttpStatus.BAD_GATEWAY, "MCP_TRANSPORT_FAILED", "MCP transport 调用失败。");
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<ConsoleErrorResponse> invalid(RuntimeException exception) {
        return error(HttpStatus.BAD_REQUEST, "MCP_CONTROL_PLANE_INVALID", exception.getMessage());
    }

    private ControlPlanePrincipal principal(String authorization) { return authenticationService.requirePrincipal(authorization); }
    private ResponseEntity<ConsoleErrorResponse> error(HttpStatus status, String code, String message) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ConsoleErrorResponse(code, message)); }
    private <T> PageResponse<T> page(List<T> values, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        int from = Math.min(values.size(), (normalizedPage - 1) * normalizedSize);
        int to = Math.min(values.size(), from + normalizedSize);
        int totalPages = values.isEmpty() ? 0 : (values.size() + normalizedSize - 1) / normalizedSize;
        return new PageResponse<>(values.subList(from, to), normalizedPage, normalizedSize, values.size(), totalPages);
    }
}
