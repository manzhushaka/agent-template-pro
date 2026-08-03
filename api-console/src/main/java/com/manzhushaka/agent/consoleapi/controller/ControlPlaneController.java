package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlaneAudit;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
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
public class ControlPlaneController {
    private final ConsoleAuthenticationService authenticationService;
    private final ControlPlaneService controlPlaneService;

    public ControlPlaneController(
            ConsoleAuthenticationService authenticationService,
            ControlPlaneService controlPlaneService
    ) {
        this.authenticationService = authenticationService;
        this.controlPlaneService = controlPlaneService;
    }

    @GetMapping("/control-plane/me")
    public ControlPlanePrincipal me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return principal(authorization);
    }

    @GetMapping("/models/providers")
    public PageResponse<Map<String, Object>> providers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(controlPlaneService.providers(principal(authorization), keyword), page, size);
    }

    @PostMapping("/models/providers")
    public Map<String, Object> createProvider(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveProvider(principal(authorization), null, request);
    }

    @PutMapping("/models/providers/{id}")
    public Map<String, Object> updateProvider(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveProvider(principal(authorization), id, request);
    }

    @GetMapping("/models")
    public PageResponse<Map<String, Object>> models(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String modelType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(controlPlaneService.models(principal(authorization), keyword, modelType), page, size);
    }

    @PostMapping("/models")
    public Map<String, Object> createModel(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveModel(principal(authorization), null, request);
    }

    @PutMapping("/models/{id}")
    public Map<String, Object> updateModel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveModel(principal(authorization), id, request);
    }

    @PostMapping("/models/{id}:test")
    public Map<String, Object> testModel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.testModel(principal(authorization), id);
    }

    @GetMapping("/secret-refs")
    public List<Map<String, Object>> secretRefs(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.secretRefs(principal(authorization));
    }

    @PostMapping("/secret-refs")
    public Map<String, Object> createSecretRef(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveSecretRef(principal(authorization), null, request);
    }

    @PutMapping("/secret-refs/{id}")
    public Map<String, Object> updateSecretRef(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.saveSecretRef(principal(authorization), id, request);
    }

    @GetMapping("/prompts")
    public PageResponse<Map<String, Object>> prompts(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(controlPlaneService.prompts(principal(authorization), keyword), page, size);
    }

    @PostMapping("/prompts")
    public Map<String, Object> createPrompt(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.savePrompt(principal(authorization), null, request);
    }

    @PutMapping("/prompts/{id}")
    public Map<String, Object> updatePrompt(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return controlPlaneService.savePrompt(principal(authorization), id, request);
    }

    @GetMapping("/prompts/{id}/versions")
    public List<Map<String, Object>> promptVersions(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.promptVersions(principal(authorization), id);
    }

    @PostMapping("/prompts/{id}/versions")
    public Map<String, Object> createPromptVersion(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.createVersion(principal(authorization), id);
    }

    @PostMapping("/prompt-versions/{id}:debug")
    public Map<String, Object> debugPrompt(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) Map<String, Object> variables
    ) {
        return controlPlaneService.debugPrompt(principal(authorization), id, variables == null ? Map.of() : variables);
    }

    @PostMapping("/prompt-versions/{id}:publish")
    public Map<String, Object> publishPrompt(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.publishPrompt(principal(authorization), id);
    }

    @PostMapping("/prompts/{id}:rollback")
    public Map<String, Object> rollbackPrompt(
            @PathVariable String id,
            @RequestParam String versionId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.rollbackPrompt(principal(authorization), id, versionId);
    }

    @GetMapping("/control-plane/audits")
    public List<ControlPlaneAudit> audits(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return controlPlaneService.audits(principal(authorization));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ControlPlaneAccessDeniedException.class)
    public ResponseEntity<ConsoleErrorResponse> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_PERMISSION_DENIED", "当前管理员没有执行此操作的权限。"));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> unauthenticated(ConsoleAuthenticationException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse(exception.reason().code(), exception.reason().message()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ConsoleErrorResponse> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONTROL_PLANE_INVALID", exception.getMessage()));
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
}
