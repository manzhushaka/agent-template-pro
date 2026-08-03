package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Console-only Agent application, version, publish/rollback and API-key management. API keys are
 * stored and returned as hashes plus a short display prefix; the plaintext key is returned once
 * at creation and rotation.
 */
@RestController
@RequestMapping("/api/console/v1")
public class AgentApplicationController {
    private final ConsoleAuthenticationService authenticationService;
    private final AgentApplicationService applicationService;

    public AgentApplicationController(
            ConsoleAuthenticationService authenticationService,
            AgentApplicationService applicationService
    ) {
        this.authenticationService = authenticationService;
        this.applicationService = applicationService;
    }

    @GetMapping("/applications")
    public PageResponse<Map<String, Object>> applications(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(applicationService.applications(principal(authorization), keyword), page, size);
    }

    @PostMapping("/applications")
    public Map<String, Object> createApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return applicationService.saveApplication(principal(authorization), null, request);
    }

    @PutMapping("/applications/{id}")
    public Map<String, Object> updateApplication(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return applicationService.saveApplication(principal(authorization), id, request);
    }

    @PostMapping("/applications/{id}:archive")
    public ResponseEntity<Void> archiveApplication(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        applicationService.archiveApplication(principal(authorization), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/applications/{id}/versions")
    public List<Map<String, Object>> versions(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.versions(principal(authorization), id);
    }

    @GetMapping("/applications/{id}/versions/{versionId}")
    public Map<String, Object> version(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.version(principal(authorization), versionId);
    }

    @GetMapping("/applications/{id}/versions/{versionId}/bindings")
    public List<Map<String, Object>> versionBindings(
            @PathVariable String id,
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.versionBindings(principal(authorization), versionId);
    }

    @PostMapping("/applications/{id}/versions")
    public Map<String, Object> createVersion(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateVersionRequest request
    ) {
        return applicationService.createVersion(principal(authorization), id, request.asInput());
    }

    @PostMapping("/application-versions/{versionId}:validate")
    public Map<String, Object> validateVersion(
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.validateVersion(principal(authorization), versionId);
    }

    @PostMapping("/application-versions/{versionId}:publish")
    public Map<String, Object> publishVersion(
            @PathVariable String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.publishVersion(principal(authorization), versionId);
    }

    @PostMapping("/applications/{id}:rollback")
    public Map<String, Object> rollback(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RollbackRequest request
    ) {
        return applicationService.rollbackApplication(principal(authorization), id, request.versionId());
    }

    @GetMapping("/applications/{id}/publish-records")
    public List<Map<String, Object>> publishRecords(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.publishRecords(principal(authorization), id);
    }

    @GetMapping("/applications/{id}/api-keys")
    public List<Map<String, Object>> apiKeys(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.apiKeys(principal(authorization), id);
    }

    @PostMapping("/applications/{id}/api-keys")
    public Map<String, Object> createApiKey(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateApiKeyRequest request
    ) {
        return applicationService.createApiKey(
                principal(authorization),
                id,
                request.expiresAt() == null || request.expiresAt().isBlank() ? null : Instant.parse(request.expiresAt()),
                request.scopes());
    }

    @PostMapping("/applications/{id}/api-keys/{keyId}:rotate")
    public Map<String, Object> rotateApiKey(
            @PathVariable String id,
            @PathVariable String keyId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.rotateApiKey(principal(authorization), id, keyId);
    }

    @PostMapping("/applications/{id}/api-keys/{keyId}:revoke")
    public ResponseEntity<Void> revokeApiKey(
            @PathVariable String id,
            @PathVariable String keyId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        applicationService.revokeApiKey(principal(authorization), id, keyId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/applications/{id}:openapi")
    public Map<String, Object> openApiSpec(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return applicationService.openApiSpec(principal(authorization), id);
    }

    @ExceptionHandler(ControlPlaneAccessDeniedException.class)
    public ResponseEntity<ConsoleErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "CONSOLE_PERMISSION_DENIED", "当前管理员没有执行此操作的权限。");
    }

    @ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> unauthenticated(ConsoleAuthenticationException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.reason().code(), exception.reason().message());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ConsoleErrorResponse> invalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "AGENT_APP_REQUEST_INVALID", safeMessage(exception, "Agent 应用请求无效。"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ConsoleErrorResponse> conflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, "AGENT_APP_STATE_CONFLICT", safeMessage(exception, "Agent 应用状态冲突。"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ConsoleErrorResponse> duplicate() {
        return error(HttpStatus.CONFLICT, "AGENT_APP_RESOURCE_CONFLICT", "Agent 应用编码或 API Key 已存在。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ConsoleErrorResponse> failed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_APP_CONTROL_FAILED", "Agent 应用服务暂时不可用。");
    }

    private ControlPlanePrincipal principal(String authorization) {
        return authenticationService.requirePrincipal(authorization);
    }

    private <T> PageResponse<T> page(List<T> values, int page, int size) {
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.min(100, Math.max(1, size));
        int from = Math.min(values.size(), (normalizedPage - 1) * normalizedSize);
        int to = Math.min(values.size(), from + normalizedSize);
        int totalPages = values.isEmpty() ? 0 : (values.size() + normalizedSize - 1) / normalizedSize;
        return new PageResponse<>(values.subList(from, to), normalizedPage, normalizedSize, values.size(), totalPages);
    }

    private ResponseEntity<ConsoleErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ConsoleErrorResponse(code, message));
    }

    private String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() || message.length() > 200 ? fallback : message;
    }

    public record CreateVersionRequest(
            @NotBlank @Size(max = 120) String modelCode,
            @NotBlank @Size(max = 100) String promptId,
            @NotBlank @Size(max = 100) String promptVersionId,
            @Size(max = 100) String knowledgeBaseId,
            Map<String, Object> config,
            List<BindingInput> bindings
    ) {
        Map<String, Object> asInput() {
            List<Map<String, Object>> bindingValues = bindings == null ? List.of()
                    : bindings.stream().map(BindingInput::asMap).toList();
            Map<String, Object> input = new java.util.LinkedHashMap<>();
            input.put("modelCode", modelCode);
            input.put("promptId", promptId);
            input.put("promptVersionId", promptVersionId);
            if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
                input.put("knowledgeBaseId", knowledgeBaseId);
            }
            input.put("config", config == null ? Map.of() : config);
            input.put("bindings", bindingValues);
            return Map.copyOf(input);
        }
    }

    public record BindingInput(
            @NotBlank @Size(max = 32) String resourceType,
            @NotBlank @Size(max = 120) String resourceId,
            @Size(max = 120) String resourceVersion
    ) {
        Map<String, Object> asMap() {
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("resourceType", resourceType.trim().toUpperCase(Locale.ROOT));
            value.put("resourceId", resourceId);
            if (resourceVersion != null && !resourceVersion.isBlank()) {
                value.put("resourceVersion", resourceVersion);
            }
            return Map.copyOf(value);
        }
    }

    public record RollbackRequest(@NotBlank @Size(max = 100) String versionId) {
    }

    public record CreateApiKeyRequest(
            @Size(max = 40) String expiresAt,
            @Size(max = 10) List<String> scopes
    ) {
    }
}
