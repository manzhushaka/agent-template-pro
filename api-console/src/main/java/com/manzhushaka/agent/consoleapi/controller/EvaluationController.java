package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.service.ConsoleResourceNotFoundException;
import com.manzhushaka.agent.controlplane.evaluation.EvaluationService;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.trace.TraceQueryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/console/v1/evaluation")
public class EvaluationController {
    private final ConsoleAuthenticationService authenticationService;
    private final EvaluationService evaluationService;
    private final TraceQueryPort traceQueryPort;

    public EvaluationController(
            ConsoleAuthenticationService authenticationService,
            EvaluationService evaluationService,
            TraceQueryPort traceQueryPort
    ) {
        this.authenticationService = authenticationService;
        this.evaluationService = evaluationService;
        this.traceQueryPort = traceQueryPort;
    }

    @GetMapping("/datasets")
    public PageResponse<Map<String, Object>> datasets(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "query", required = false) String query
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        var source = evaluationService.datasets(principal, query, page, size);
        return page(source.items(), page, size, source.total());
    }

    @PostMapping("/datasets")
    public Map<String, Object> createDataset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.createDataset(principal, input);
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> dataset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        return evaluationService.dataset(principal, id);
    }

    @GetMapping("/datasets/{id}/versions")
    public List<Map<String, Object>> datasetVersions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        return evaluationService.datasetVersions(principal, id);
    }

    @PostMapping("/datasets/{id}/versions")
    public Map<String, Object> createDatasetVersion(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.createDatasetVersion(principal, id, input);
    }

    @GetMapping("/datasets/versions/{versionId}/cases")
    public PageResponse<Map<String, Object>> cases(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String versionId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "query", required = false) String query
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        var source = evaluationService.cases(principal, versionId, category, query, page, size);
        return page(source.items(), page, size, source.total());
    }

    @PostMapping("/datasets/versions/{versionId}/cases")
    public Map<String, Object> addCase(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String versionId,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.addCase(principal, versionId, input);
    }

    @PostMapping("/datasets/versions/{versionId}/cases:import")
    public Map<String, Object> importCases(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String versionId,
            @RequestBody List<Map<String, Object>> cases
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        long imported = evaluationService.importCases(principal, versionId, cases);
        return Map.of("imported", imported);
    }

    @PostMapping("/datasets/versions/{versionId}/cases:generate-from-trace")
    public Map<String, Object> generateFromTrace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String versionId,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        String traceId = required(input, "traceId");
        List<SpanRecord> spans = traceQueryPort.trace(traceId);
        if (spans.isEmpty()) {
            throw new ConsoleResourceNotFoundException("Trace 不存在或为空。");
        }
        String inputText = input.get("inputText") == null ? "(trace 生成候选，请补充输入)" : String.valueOf(input.get("inputText"));
        String category = input.get("category") == null ? "trace-generated" : String.valueOf(input.get("category"));
        Map<String, Object> expected = expectedFromSpans(spans);
        Map<String, Object> caseInput = new LinkedHashMap<>();
        caseInput.put("caseKey", "trace-" + traceId.replaceAll("[^A-Za-z0-9_-]", "-").substring(0, Math.min(48, traceId.length())));
        caseInput.put("category", category);
        caseInput.put("source", "TRACE");
        caseInput.put("traceId", traceId);
        Map<String, Object> inputMap = new LinkedHashMap<>();
        inputMap.put("text", inputText);
        caseInput.put("input", inputMap);
        caseInput.put("expected", expected);
        return evaluationService.addCase(principal, versionId, caseInput);
    }

    @GetMapping("/evaluators")
    public PageResponse<Map<String, Object>> evaluators(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "query", required = false) String query
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        var source = evaluationService.evaluators(principal, query, page, size);
        return page(source.items(), page, size, source.total());
    }

    @PostMapping("/evaluators")
    public Map<String, Object> createEvaluator(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.createEvaluator(principal, input);
    }

    @PostMapping("/evaluators/{id}/versions")
    public Map<String, Object> createEvaluatorVersion(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.createEvaluatorVersion(principal, id, input);
    }

    @GetMapping("/experiments")
    public PageResponse<Map<String, Object>> experiments(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "query", required = false) String query
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        var source = evaluationService.experiments(principal, query, status, page, size);
        return page(source.items(), page, size, source.total());
    }

    @PostMapping("/experiments")
    public Map<String, Object> createExperiment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> input
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_WRITE);
        return evaluationService.createExperiment(principal, input);
    }

    @GetMapping("/experiments/{id}")
    public Map<String, Object> experiment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        return evaluationService.experiment(principal, id);
    }

    @PostMapping("/experiments/{id}:start")
    public Map<String, Object> start(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_RUN);
        return evaluationService.startExperiment(principal, id);
    }

    @PostMapping("/experiments/{id}:stop")
    public Map<String, Object> stop(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_RUN);
        return evaluationService.stopExperiment(principal, id);
    }

    @PostMapping("/experiments/{id}:retry")
    public Map<String, Object> retry(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_RUN);
        return evaluationService.retryExperiment(principal, id);
    }

    @GetMapping("/experiments/{id}/results")
    public PageResponse<Map<String, Object>> results(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        var source = evaluationService.experimentRuns(principal, id, page, size);
        return page(source.items(), page, size, source.total());
    }

    @GetMapping("/experiments/{id}/summary")
    public Map<String, Object> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        var principal = authenticationService.requirePermission(authorization, EvaluationService.EVAL_READ);
        return evaluationService.experimentResultSummary(principal, id);
    }

    private static Map<String, Object> expectedFromSpans(List<SpanRecord> spans) {
        Map<String, Object> expected = new LinkedHashMap<>();
        spans.stream()
                .filter(span -> span.type() == SpanType.ROUTE && span.agentCode() != null)
                .findFirst()
                .ifPresent(span -> expected.put("agentCode", span.agentCode()));
        spans.stream()
                .filter(span -> span.type() == SpanType.ACTION && span.actionCode() != null)
                .findFirst()
                .ifPresent(span -> expected.put("actionCode", span.actionCode()));
        List<String> toolCodes = spans.stream()
                .filter(span -> span.type() == SpanType.TOOL && span.toolCode() != null)
                .map(SpanRecord::toolCode)
                .distinct()
                .toList();
        if (!toolCodes.isEmpty()) {
            expected.put("toolCode", toolCodes.getFirst());
        }
        spans.stream()
                .filter(span -> span.type() == SpanType.TASK && "task.confirmation".equals(span.name()))
                .findFirst()
                .ifPresent(span -> expected.put("confirmationVersion", 1));
        return expected;
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ConsoleResourceNotFoundException("缺少必填字段: " + key);
        }
        return String.valueOf(value).trim();
    }

    private static <T> PageResponse<T> page(List<T> items, int page, int size, long total) {
        int pageSize = Math.max(1, Math.min(size, 100));
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / pageSize);
        return new PageResponse<>(items, Math.max(1, page), pageSize, total, totalPages);
    }
}
