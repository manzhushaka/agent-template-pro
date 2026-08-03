package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.LinkedHashMap;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Console-only knowledge source management. Source objects and chunk text never leave this
 * boundary; retrieval tests expose citation metadata only.
 */
@RestController
@RequestMapping("/api/console/v1")
public class KnowledgeBaseController {
    private static final int MAX_DOCUMENT_BYTES = 10 * 1024 * 1024;
    private static final int MAX_BASE64_DOCUMENT_CHARACTERS = ((MAX_DOCUMENT_BYTES + 2) / 3) * 4;
    private final ConsoleAuthenticationService authenticationService;
    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(
            ConsoleAuthenticationService authenticationService,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.authenticationService = authenticationService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @GetMapping("/knowledge-bases")
    public PageResponse<Map<String, Object>> knowledgeBases(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(filter(knowledgeBaseService.knowledgeBases(principal(authorization)), keyword, "id", "code", "displayName"), page, size);
    }

    @PostMapping("/knowledge-bases")
    public Map<String, Object> createKnowledgeBase(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return knowledgeBaseService.saveKnowledgeBase(principal(authorization), null, request);
    }

    @PutMapping("/knowledge-bases/{id}")
    public Map<String, Object> updateKnowledgeBase(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return knowledgeBaseService.saveKnowledgeBase(principal(authorization), id, request);
    }

    @GetMapping("/knowledge-bases/{id}/documents")
    public PageResponse<Map<String, Object>> documents(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(filter(knowledgeBaseService.documents(principal(authorization), id), keyword, "id", "name", "contentType", "status"), page, size);
    }

    @PostMapping(value = "/knowledge-bases/{id}/documents", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> uploadDocument(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody DocumentUploadRequest request
    ) {
        return knowledgeBaseService.uploadDocument(
                principal(authorization), id, request.name(), request.contentType(), request.bytes()
        );
    }

    @PostMapping("/documents/{id}:reindex")
    public Map<String, Object> reindexDocument(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return knowledgeBaseService.reindexDocument(principal(authorization), id);
    }

    @DeleteMapping("/documents/{id}")
    public ResponseEntity<Void> deleteDocument(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        knowledgeBaseService.deleteDocument(principal(authorization), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @GetMapping("/documents/{id}/chunks:preview")
    public PageResponse<Map<String, Object>> chunksPreview(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(knowledgeBaseService.chunksPreview(principal(authorization), id).stream()
                .map(this::withoutChunkContent).toList(), page, size);
    }

    @PutMapping("/documents/{id}/chunks")
    public Map<String, Object> setChunkEnabled(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChunkStatusRequest request
    ) {
        return withoutChunkContent(knowledgeBaseService.setChunkEnabled(
                principal(authorization), id, request.chunkId(), request.enabled()
        ));
    }

    @GetMapping("/knowledge-index-jobs")
    public PageResponse<Map<String, Object>> indexJobs(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String knowledgeBaseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return page(filter(knowledgeBaseService.indexJobs(principal(authorization), knowledgeBaseId), status, "status"), page, size);
    }

    @PostMapping("/knowledge-index-jobs/{id}:retry")
    public Map<String, Object> retryIndexJob(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return knowledgeBaseService.retryIndexJob(principal(authorization), id);
    }

    @PostMapping("/knowledge-bases/{id}:retrieve-test")
    public List<Map<String, Object>> retrieve(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RetrievalTestRequest request
    ) {
        return knowledgeBaseService.retrieve(
                        principal(authorization), id, request.query(), request.topK(), request.threshold()
                ).stream()
                .map(this::withoutCitationContent)
                .toList();
    }

    @ExceptionHandler(ControlPlaneAccessDeniedException.class)
    public ResponseEntity<ConsoleErrorResponse> forbidden() {
        return error(HttpStatus.FORBIDDEN, "CONSOLE_PERMISSION_DENIED", "当前管理员没有执行此操作的权限。");
    }

    @ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> unauthenticated(ConsoleAuthenticationException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.reason().code(), exception.reason().message());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ConsoleErrorResponse> invalidRequest() {
        return error(HttpStatus.BAD_REQUEST, "KNOWLEDGE_REQUEST_INVALID", "知识库请求无效。");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ConsoleErrorResponse> invalid(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "KNOWLEDGE_CONTROL_INVALID", safeMessage(exception, "知识库请求无效。"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ConsoleErrorResponse> conflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, "KNOWLEDGE_CONTROL_CONFLICT", safeMessage(exception, "知识库资源状态冲突。"));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ConsoleErrorResponse> duplicateResource() {
        return error(HttpStatus.CONFLICT, "KNOWLEDGE_RESOURCE_CONFLICT", "知识库编码或索引资源已存在。");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ConsoleErrorResponse> failed() {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "KNOWLEDGE_CONTROL_FAILED", "知识库服务暂时不可用。");
    }

    private ControlPlanePrincipal principal(String authorization) {
        return authenticationService.requirePrincipal(authorization);
    }

    private Map<String, Object> withoutChunkContent(Map<String, Object> chunk) {
        Map<String, Object> safe = new LinkedHashMap<>(chunk);
        safe.remove("content");
        safe.remove("contentPreview");
        return Map.copyOf(safe);
    }

    private Map<String, Object> withoutCitationContent(Map<String, Object> result) {
        Map<String, Object> safe = new LinkedHashMap<>(result);
        Object citationValue = safe.get("citation");
        if (citationValue instanceof Map<?, ?> citation) {
            Map<String, Object> safeCitation = new LinkedHashMap<>();
            citation.forEach((key, value) -> {
                if (!"content".equals(key) && !"contentPreview".equals(key)) {
                    safeCitation.put(String.valueOf(key), value);
                }
            });
            safe.put("citation", Map.copyOf(safeCitation));
        }
        return Map.copyOf(safe);
    }

    private List<Map<String, Object>> filter(List<Map<String, Object>> values, String keyword, String... fields) {
        if (keyword == null || keyword.isBlank()) {
            return values;
        }
        String normalized = keyword.trim().toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> java.util.Arrays.stream(fields)
                .map(value::get)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .anyMatch(candidate -> candidate.toLowerCase(Locale.ROOT).contains(normalized)))
                .toList();
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

    public record DocumentUploadRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 100) String contentType,
            @Size(max = MAX_DOCUMENT_BYTES) String content,
            @Size(max = MAX_BASE64_DOCUMENT_CHARACTERS) String contentBase64
    ) {
        byte[] bytes() {
            byte[] bytes;
            if (content != null && !content.isBlank() && (contentBase64 == null || contentBase64.isBlank())) {
                bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else if (contentBase64 != null && !contentBase64.isBlank() && (content == null || content.isBlank())) {
                try {
                    bytes = Base64.getDecoder().decode(contentBase64);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException("文档内容编码无效。");
                }
            } else {
                throw new IllegalArgumentException("必须且只能提交一种文档内容。");
            }
            if (bytes.length == 0 || bytes.length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("文档为空或超过 10 MB 限制。");
            }
            return bytes;
        }
    }

    public record ChunkStatusRequest(@NotBlank @Size(max = 100) String chunkId, boolean enabled) {
    }

    public record RetrievalTestRequest(
            @NotBlank @Size(max = 4_000) String query,
            @Min(1) @Max(50) int topK,
            @DecimalMin("0.0") @DecimalMax("1.0") double threshold
    ) {
    }
}
