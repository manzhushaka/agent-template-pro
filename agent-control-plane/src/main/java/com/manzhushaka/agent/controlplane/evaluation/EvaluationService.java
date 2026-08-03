package com.manzhushaka.agent.controlplane.evaluation;

import com.manzhushaka.agent.common.mask.SensitiveMasker;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Evaluation use cases: dataset/case/evaluator/experiment management, experiment lifecycle
 * (start/stop/retry), restart-recoverable worker execution and result comparison. All views
 * and exports mask sensitive content; raw case text stays at rest only.
 */
@Service
public class EvaluationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationService.class);

    public static final String EVAL_READ = "eval:read";
    public static final String EVAL_WRITE = "eval:write";
    public static final String EVAL_RUN = "eval:run";
    private static final long COST_MICROS_PER_TOKEN = 10L;

    private final EvaluationRepository repository;
    private final AgentApplicationService agentApplicationService;
    private final List<EvaluatorPlugin> plugins;
    private final AgentEvaluationExecutor executor;

    public EvaluationService(
            EvaluationRepository repository,
            AgentApplicationService agentApplicationService,
            List<EvaluatorPlugin> plugins,
            AgentEvaluationExecutor executor
    ) {
        this.repository = repository;
        this.agentApplicationService = agentApplicationService;
        this.plugins = plugins;
        this.executor = executor;
    }

    public void require(ControlPlanePrincipal principal, String permission) {
        if (principal == null || !principal.permissions().contains(permission)) {
            throw new ControlPlaneAccessDeniedException();
        }
    }

    public Map<String, Object> createDataset(ControlPlanePrincipal principal, Map<String, Object> input) {
        require(principal, EVAL_WRITE);
        String code = required(input, "code");
        if (repository.datasetByCode(code).isPresent()) {
            throw new IllegalArgumentException("数据集编码已存在: " + code);
        }
        Instant now = Instant.now();
        EvalDataset dataset = new EvalDataset(
                "eds_" + UUID.randomUUID(), code, required(input, "displayName"),
                optional(input, "description"), null, "ACTIVE", principal.username(), now, now
        );
        repository.saveDataset(dataset);
        EvalDatasetVersion version = createDatasetVersionInternal(principal, dataset.id(), "v1", "初始版本");
        repository.saveDataset(repository.dataset(dataset.id()).map(current -> new EvalDataset(
                current.id(), current.code(), current.displayName(), current.description(), version.id(),
                current.status(), current.createdBy(), current.createdAt(), Instant.now()
        )).orElse(dataset));
        return datasetView(repository.dataset(dataset.id()).orElse(dataset));
    }

    public EvalPage<Map<String, Object>> datasets(ControlPlanePrincipal principal, String keyword, int page, int size) {
        require(principal, EVAL_READ);
        EvalPage<EvalDataset> source = repository.datasets(keyword, page, size);
        return new EvalPage<>(source.items().stream().map(this::datasetView).toList(), source.total());
    }

    public Map<String, Object> dataset(ControlPlanePrincipal principal, String id) {
        require(principal, EVAL_READ);
        return datasetView(requireDataset(id));
    }

    public Map<String, Object> createDatasetVersion(
            ControlPlanePrincipal principal,
            String datasetId,
            Map<String, Object> input
    ) {
        require(principal, EVAL_WRITE);
        EvalDataset dataset = requireDataset(datasetId);
        List<EvalDatasetVersion> versions = repository.datasetVersions(datasetId);
        int next = versions.stream().mapToInt(EvalDatasetVersion::versionNo).max().orElse(0) + 1;
        String description = optional(input, "description");
        EvalDatasetVersion version = createDatasetVersionInternal(principal, datasetId, "v" + next, description);
        repository.saveDataset(new EvalDataset(
                dataset.id(), dataset.code(), dataset.displayName(), dataset.description(), version.id(),
                dataset.status(), dataset.createdBy(), dataset.createdAt(), Instant.now()
        ));
        return versionView(version);
    }

    public List<Map<String, Object>> datasetVersions(ControlPlanePrincipal principal, String datasetId) {
        require(principal, EVAL_READ);
        requireDataset(datasetId);
        return repository.datasetVersions(datasetId).stream().map(this::versionView).toList();
    }

    public Map<String, Object> addCase(
            ControlPlanePrincipal principal,
            String datasetVersionId,
            Map<String, Object> input
    ) {
        require(principal, EVAL_WRITE);
        EvalDatasetVersion version = requireDatasetVersion(datasetVersionId);
        String caseKey = required(input, "caseKey");
        if (repository.caseByKey(datasetVersionId, caseKey).isPresent()) {
            throw new IllegalArgumentException("用例键已存在: " + caseKey);
        }
        EvalCase evalCase = new EvalCase(
                "ecs_" + UUID.randomUUID(), version.datasetId(), datasetVersionId, caseKey,
                defaulted(input, "category", "manual"),
                map(input.get("input")), map(input.get("expected")), map(input.get("tags")),
                defaulted(input, "source", "MANUAL"), optional(input, "traceId"),
                principal.username(), Instant.now()
        );
        repository.saveCase(evalCase);
        return caseView(evalCase);
    }

    public long importCases(
            ControlPlanePrincipal principal,
            String datasetVersionId,
            List<Map<String, Object>> cases
    ) {
        require(principal, EVAL_WRITE);
        requireDatasetVersion(datasetVersionId);
        long imported = 0;
        for (Map<String, Object> item : cases) {
            String caseKey = required(item, "caseKey");
            if (repository.caseByKey(datasetVersionId, caseKey).isEmpty()) {
                EvalDatasetVersion version = repository.datasetVersion(datasetVersionId).orElseThrow();
                repository.saveCase(new EvalCase(
                        "ecs_" + UUID.randomUUID(), version.datasetId(), datasetVersionId, caseKey,
                        defaulted(item, "category", "manual"), map(item.get("input")),
                        map(item.get("expected")), map(item.get("tags")),
                        defaulted(item, "source", "BULK"), optional(item, "traceId"),
                        principal.username(), Instant.now()
                ));
                imported++;
            }
        }
        return imported;
    }

    public EvalPage<Map<String, Object>> cases(
            ControlPlanePrincipal principal,
            String datasetVersionId,
            String category,
            String keyword,
            int page,
            int size
    ) {
        require(principal, EVAL_READ);
        requireDatasetVersion(datasetVersionId);
        EvalPage<EvalCase> source = repository.cases(datasetVersionId, category, keyword, page, size);
        return new EvalPage<>(source.items().stream().map(this::caseView).toList(), source.total());
    }

    public Map<String, Object> createEvaluator(ControlPlanePrincipal principal, Map<String, Object> input) {
        require(principal, EVAL_WRITE);
        String code = required(input, "code");
        if (repository.evaluatorByCode(code).isPresent()) {
            throw new IllegalArgumentException("评估器编码已存在: " + code);
        }
        String type = required(input, "evaluatorType").toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        EvalEvaluator evaluator = new EvalEvaluator(
                "eev_" + UUID.randomUUID(), code, required(input, "displayName"), type,
                optional(input, "description"), "ACTIVE", null, principal.username(), now, now
        );
        repository.saveEvaluator(evaluator);
        EvalEvaluatorVersion version = saveEvaluatorVersionInternal(
                principal, evaluator.id(), 1, map(input.get("config"))
        );
        repository.saveEvaluator(repository.evaluator(evaluator.id()).map(current -> new EvalEvaluator(
                current.id(), current.code(), current.displayName(), current.evaluatorType(),
                current.description(), current.status(), version.id(), current.createdBy(),
                current.createdAt(), Instant.now()
        )).orElse(evaluator));
        return evaluatorView(repository.evaluator(evaluator.id()).orElse(evaluator));
    }

    public EvalPage<Map<String, Object>> evaluators(ControlPlanePrincipal principal, String keyword, int page, int size) {
        require(principal, EVAL_READ);
        EvalPage<EvalEvaluator> source = repository.evaluators(keyword, page, size);
        return new EvalPage<>(source.items().stream().map(this::evaluatorView).toList(), source.total());
    }

    public Map<String, Object> createEvaluatorVersion(
            ControlPlanePrincipal principal,
            String evaluatorId,
            Map<String, Object> input
    ) {
        require(principal, EVAL_WRITE);
        EvalEvaluator evaluator = requireEvaluator(evaluatorId);
        List<EvalEvaluatorVersion> versions = repository.evaluatorVersions(evaluatorId);
        int next = versions.stream().mapToInt(EvalEvaluatorVersion::versionNo).max().orElse(0) + 1;
        EvalEvaluatorVersion version = saveEvaluatorVersionInternal(
                principal, evaluatorId, next, map(input.get("config"))
        );
        repository.saveEvaluator(new EvalEvaluator(
                evaluator.id(), evaluator.code(), evaluator.displayName(), evaluator.evaluatorType(),
                evaluator.description(), evaluator.status(), version.id(), evaluator.createdBy(),
                evaluator.createdAt(), Instant.now()
        ));
        return evaluatorVersionView(version);
    }

    public Map<String, Object> createExperiment(ControlPlanePrincipal principal, Map<String, Object> input) {
        require(principal, EVAL_WRITE);
        String code = required(input, "code");
        if (repository.experimentByCode(code).isPresent()) {
            throw new IllegalArgumentException("实验编码已存在: " + code);
        }
        String datasetVersionId = required(input, "datasetVersionId");
        String agentVersionId = required(input, "agentVersionId");
        requireDatasetVersion(datasetVersionId);
        validateAgentVersion(principal, agentVersionId);
        Object evaluatorIdsValue = input.get("evaluatorVersionIds");
        List<String> evaluatorVersionIds = evaluatorIdsValue instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        if (evaluatorVersionIds.isEmpty()) {
            throw new IllegalArgumentException("至少绑定一个评估器版本");
        }
        for (String evaluatorVersionId : evaluatorVersionIds) {
            EvalEvaluatorVersion version = repository.evaluatorVersion(evaluatorVersionId)
                    .orElseThrow(() -> new IllegalArgumentException("评估器版本不存在: " + evaluatorVersionId));
            if (!"ACTIVE".equals(version.status())) {
                throw new IllegalArgumentException("评估器版本未启用: " + evaluatorVersionId);
            }
        }
        long totalCases = repository.countCases(datasetVersionId);
        Instant now = Instant.now();
        EvalExperiment experiment = new EvalExperiment(
                "eex_" + UUID.randomUUID(), code, required(input, "displayName"), datasetIdOf(datasetVersionId),
                datasetVersionId, optional(input, "agentApplicationId"), agentVersionId,
                evaluatorVersionIds, "DRAFT", runKey(datasetVersionId, agentVersionId), (int) totalCases,
                0, 0, 0, 0, 0L,
                decimal(input.get("thresholdPassRate")), null, null, null, null, null,
                principal.username(), now, now
        );
        repository.saveExperiment(experiment);
        return experimentView(experiment);
    }

    public EvalPage<Map<String, Object>> experiments(
            ControlPlanePrincipal principal,
            String keyword,
            String status,
            int page,
            int size
    ) {
        require(principal, EVAL_READ);
        EvalPage<EvalExperiment> source = repository.experiments(keyword, status, page, size);
        return new EvalPage<>(source.items().stream().map(this::experimentView).toList(), source.total());
    }

    public Map<String, Object> experiment(ControlPlanePrincipal principal, String id) {
        require(principal, EVAL_READ);
        return experimentView(requireExperiment(id));
    }

    public Map<String, Object> startExperiment(ControlPlanePrincipal principal, String id) {
        require(principal, EVAL_RUN);
        EvalExperiment experiment = requireExperiment(id);
        if (!"DRAFT".equals(experiment.status()) && !"STOPPED".equals(experiment.status())) {
            throw new IllegalStateException("实验状态不允许启动: " + experiment.status());
        }
        if (experiment.totalCases() == 0) {
            throw new IllegalStateException("数据集没有可用用例");
        }
        if (executor == null) {
            throw new IllegalStateException("评估执行器未配置");
        }
        Instant now = Instant.now();
        EvalExperiment started = new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), "RUNNING", experiment.runKey(), experiment.totalCases(),
                0, 0, 0, 0, 0L, experiment.thresholdPassRate(), null,
                null, null, now, null, experiment.createdBy(), experiment.createdAt(), now
        );
        repository.saveExperiment(started);
        return experimentView(started);
    }

    public Map<String, Object> stopExperiment(ControlPlanePrincipal principal, String id) {
        require(principal, EVAL_RUN);
        EvalExperiment experiment = requireExperiment(id);
        if (!"RUNNING".equals(experiment.status())) {
            throw new IllegalStateException("实验不在运行中");
        }
        EvalExperiment stopped = new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), "STOPPED", experiment.runKey(), experiment.totalCases(),
                experiment.completedCases(), experiment.passedCases(), experiment.failedCases(),
                experiment.errorCases(), experiment.costMicros(), experiment.thresholdPassRate(),
                experiment.passRate(), null, null, experiment.startedAt(), Instant.now(),
                experiment.createdBy(), experiment.createdAt(), Instant.now()
        );
        repository.saveExperiment(stopped);
        repository.releaseExperiment(id);
        return experimentView(stopped);
    }

    public Map<String, Object> retryExperiment(ControlPlanePrincipal principal, String id) {
        require(principal, EVAL_RUN);
        EvalExperiment experiment = requireExperiment(id);
        if (!"STOPPED".equals(experiment.status()) && !"PARTIAL".equals(experiment.status())) {
            throw new IllegalStateException("只有已停止或部分完成的实验可以重试");
        }
        for (EvalExperimentRun run : repository.runsByExperiment(id)) {
            if (run.passed() == null || !run.passed()) {
                EvalExperimentRun reset = new EvalExperimentRun(
                        run.id(), run.experimentId(), run.caseId(), run.caseKey(), "PENDING",
                        null, null, null, List.of(), null, 0, 0L,
                        null, null, run.createdAt(), Instant.now()
                );
                repository.saveRun(reset);
            }
        }
        Instant now = Instant.now();
        EvalExperiment restarted = new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), "RUNNING", experiment.runKey(), experiment.totalCases(),
                (int) repository.runsByExperiment(id).stream().filter(run -> Boolean.TRUE.equals(run.passed())).count(),
                (int) repository.runsByExperiment(id).stream().filter(run -> Boolean.TRUE.equals(run.passed())).count(),
                (int) repository.runsByExperiment(id).stream().filter(run -> Boolean.FALSE.equals(run.passed())).count(),
                (int) repository.runsByExperiment(id).stream().filter(run -> "ERROR".equals(run.status())).count(),
                experiment.costMicros(), experiment.thresholdPassRate(), null,
                null, null, experiment.startedAt(), null, experiment.createdBy(), experiment.createdAt(), now
        );
        repository.saveExperiment(restarted);
        return experimentView(restarted);
    }

    public EvalPage<Map<String, Object>> experimentRuns(
            ControlPlanePrincipal principal,
            String experimentId,
            int page,
            int size
    ) {
        require(principal, EVAL_READ);
        requireExperiment(experimentId);
        EvalPage<EvalExperimentRun> source = repository.runs(experimentId, page, size);
        return new EvalPage<>(source.items().stream().map(this::runView).toList(), source.total());
    }

    public Map<String, Object> experimentResultSummary(ControlPlanePrincipal principal, String experimentId) {
        require(principal, EVAL_READ);
        EvalExperiment experiment = requireExperiment(experimentId);
        Map<String, Object> view = new LinkedHashMap<>(experimentView(experiment));
        boolean thresholdMet = experiment.passRate() != null && experiment.thresholdPassRate() != null
                && experiment.passRate().compareTo(experiment.thresholdPassRate()) >= 0;
        view.put("passesThreshold", thresholdMet);
        view.put("thresholdMet", thresholdMet);
        return view;
    }

    /**
     * Claims one running experiment and processes its remaining cases. Restart-safe: a claim
     * lease expires and another worker resumes from pending cases.
     */
    public void processNext(String owner) {
        if (executor == null) {
            return;
        }
        Instant now = Instant.now();
        Optional<EvalExperiment> claimed = repository.claimExperiment(owner, now, now.plusSeconds(120));
        if (claimed.isEmpty()) {
            return;
        }
        EvalExperiment experiment = claimed.get();
        try {
            processClaimed(experiment);
        } finally {
            repository.releaseExperiment(experiment.id());
        }
    }

    private void processClaimed(EvalExperiment experiment) {
        List<EvalCase> cases = repository.casesByDatasetVersion(experiment.datasetVersionId());
        EvalExperiment current = experiment;
        for (EvalCase evalCase : cases) {
            Optional<EvalExperimentRun> existing = repository.run(experiment.id(), evalCase.id());
            if (existing.isPresent() && !"PENDING".equals(existing.get().status())
                    && !"ERROR".equals(existing.get().status())) {
                continue;
            }
            EvalExperimentRun result = runCase(current, evalCase);
            current = applyRun(current, result);
            repository.saveExperiment(current);
        }
        EvalExperiment finished = finish(current);
        repository.saveExperiment(finished);
    }

    private EvalExperimentRun runCase(EvalExperiment experiment, EvalCase evalCase) {
        Instant startedAt = Instant.now();
        String requestId = "evl_" + UUID.randomUUID();
        String visitorId = "eval_" + experiment.id();
        // 重试或崩溃恢复时复用既有 run 行，避免 (experiment_id, case_id) 唯一键冲突。
        Optional<EvalExperimentRun> existing = repository.run(experiment.id(), evalCase.id());
        String runId = existing.map(EvalExperimentRun::id).orElseGet(() -> "eru_" + UUID.randomUUID());
        Instant createdAt = existing.map(EvalExperimentRun::createdAt).orElse(startedAt);
        EvalExperimentRun started = new EvalExperimentRun(
                runId, experiment.id(), evalCase.id(), evalCase.caseKey(), "RUNNING",
                null, null, null, List.of(), null, 0, 0L,
                startedAt, null, createdAt, startedAt
        );
        repository.saveRun(started);
        try {
            EvaluationExecutionContext execution = new EvaluationExecutionContext(
                    experiment.id(), experiment.code(), evalCase.id(), evalCase.caseKey(),
                    appCode(experiment), experiment.agentVersionId(), visitorId, requestId,
                    evalCase.input()
            );
            EvaluationRunOutcome outcome = executor.execute(execution);
            List<Map<String, Object>> evaluatorResults = evaluate(experiment, evalCase, outcome);
            boolean passed = !evaluatorResults.isEmpty()
                    && evaluatorResults.stream().allMatch(result -> Boolean.TRUE.equals(result.get("passed")));
            BigDecimal score = BigDecimal.valueOf(passed ? 1.0 : 0.0);
            EvalExperimentRun finished = new EvalExperimentRun(
                    started.id(), experiment.id(), evalCase.id(), evalCase.caseKey(),
                    passed ? "PASSED" : "FAILED", passed, score,
                    maskText(outcome.text()), evaluatorResults, outcome.errorCode(),
                    outcome.tokensUsed(), outcome.costMicros(), startedAt, Instant.now(),
                    startedAt, Instant.now()
            );
            repository.saveRun(finished);
            return finished;
        } catch (Exception exception) {
            LOGGER.warn("评估用例执行失败: experiment={} case={} code={}",
                    experiment.code(), evalCase.caseKey(),
                    exception instanceof IllegalArgumentException ? "CASE_VALIDATION_FAILED" : "EXECUTION_FAILED",
                    exception);
            EvalExperimentRun failed = new EvalExperimentRun(
                    started.id(), experiment.id(), evalCase.id(), evalCase.caseKey(), "ERROR",
                    false, BigDecimal.ZERO, null, List.of(),
                    exception instanceof IllegalArgumentException ? "CASE_VALIDATION_FAILED" : "EXECUTION_FAILED",
                    0, 0L, startedAt, Instant.now(), startedAt, Instant.now()
            );
            repository.saveRun(failed);
            return failed;
        }
    }

    private List<Map<String, Object>> evaluate(
            EvalExperiment experiment,
            EvalCase evalCase,
            EvaluationRunOutcome outcome
    ) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String evaluatorVersionId : experiment.evaluatorVersionIds()) {
            EvalEvaluatorVersion version = repository.evaluatorVersion(evaluatorVersionId)
                    .orElseThrow(() -> new IllegalStateException("评估器版本不存在"));
            EvalEvaluator evaluator = repository.evaluator(version.evaluatorId())
                    .orElseThrow(() -> new IllegalStateException("评估器不存在"));
            EvaluatorPlugin plugin = plugins.stream()
                    .filter(candidate -> candidate.type().equalsIgnoreCase(evaluator.evaluatorType()))
                    .findFirst()
                    .orElse(null);
            EvaluatorResult result;
            if (plugin == null) {
                result = EvaluatorResult.fail(evaluator.code(), evaluator.evaluatorType(), "没有可用插件");
            } else {
                result = plugin.evaluate(new EvaluatorContext(
                        evaluatorVersionId, evaluator.code(), evaluator.evaluatorType(),
                        version.config(), evalCase, outcome
                ));
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("evaluatorVersionId", evaluatorVersionId);
            view.put("evaluatorCode", result.evaluatorCode());
            view.put("evaluatorType", result.evaluatorType());
            view.put("passed", result.passed());
            view.put("score", result.score());
            view.put("reason", result.reason());
            view.put("details", mask(result.details()));
            results.add(view);
        }
        return results;
    }

    private EvalExperiment applyRun(EvalExperiment experiment, EvalExperimentRun run) {
        int completed = experiment.completedCases() + 1;
        int passed = experiment.passedCases() + (Boolean.TRUE.equals(run.passed()) ? 1 : 0);
        int failed = experiment.failedCases() + ("FAILED".equals(run.status()) ? 1 : 0);
        int errors = experiment.errorCases() + ("ERROR".equals(run.status()) ? 1 : 0);
        return new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), "RUNNING", experiment.runKey(), experiment.totalCases(),
                completed, passed, failed, errors, experiment.costMicros() + run.costMicros(),
                experiment.thresholdPassRate(), null, experiment.claimOwner(),
                experiment.claimLeaseUntil(), experiment.startedAt(), null,
                experiment.createdBy(), experiment.createdAt(), Instant.now()
        );
    }

    private EvalExperiment finish(EvalExperiment experiment) {
        int completed = Math.min(experiment.totalCases(), experiment.completedCases());
        boolean allDone = completed >= experiment.totalCases();
        BigDecimal passRate = completed == 0 ? null
                : BigDecimal.valueOf(experiment.passedCases())
                .divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
        String status = allDone
                ? (experiment.errorCases() > 0 ? "PARTIAL" : "SUCCEEDED")
                : "STOPPED";
        return new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), status, experiment.runKey(), experiment.totalCases(),
                completed, experiment.passedCases(), experiment.failedCases(), experiment.errorCases(),
                experiment.costMicros(), experiment.thresholdPassRate(), passRate,
                experiment.claimOwner(), experiment.claimLeaseUntil(), experiment.startedAt(),
                allDone ? Instant.now() : null, experiment.createdBy(), experiment.createdAt(), Instant.now()
        );
    }

    private void validateAgentVersion(ControlPlanePrincipal principal, String agentVersionId) {
        try {
            Map<String, Object> version = agentApplicationService.version(principal, agentVersionId);
            if (!"PUBLISHED".equals(version.get("status"))) {
                throw new IllegalArgumentException("评估目标必须是已发布的 Agent 版本");
            }
        } catch (ControlPlaneAccessDeniedException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Agent 版本不存在或不可用", exception);
        }
    }

    private String appCode(EvalExperiment experiment) {
        return agentApplicationService.appCodeForVersion(experiment.agentVersionId());
    }

    private EvalDatasetVersion createDatasetVersionInternal(
            ControlPlanePrincipal principal,
            String datasetId,
            String versionCode,
            String description
    ) {
        EvalDatasetVersion version = new EvalDatasetVersion(
                "edv_" + UUID.randomUUID(), datasetId, versionNumber(versionCode), "ACTIVE",
                description, principal.username(), Instant.now(), Instant.now()
        );
        return repository.saveDatasetVersion(version);
    }

    private EvalEvaluatorVersion saveEvaluatorVersionInternal(
            ControlPlanePrincipal principal,
            String evaluatorId,
            int versionNo,
            Map<String, Object> config
    ) {
        EvalEvaluatorVersion version = new EvalEvaluatorVersion(
                "eevv_" + UUID.randomUUID(), evaluatorId, versionNo, "ACTIVE", config,
                principal.username(), Instant.now()
        );
        return repository.saveEvaluatorVersion(version);
    }

    private EvalDataset requireDataset(String id) {
        return repository.dataset(id).orElseThrow(() -> new IllegalArgumentException("数据集不存在: " + id));
    }

    private EvalDatasetVersion requireDatasetVersion(String id) {
        return repository.datasetVersion(id)
                .orElseThrow(() -> new IllegalArgumentException("数据集版本不存在: " + id));
    }

    private EvalEvaluator requireEvaluator(String id) {
        return repository.evaluator(id).orElseThrow(() -> new IllegalArgumentException("评估器不存在: " + id));
    }

    private EvalExperiment requireExperiment(String id) {
        return repository.experiment(id).orElseThrow(() -> new IllegalArgumentException("实验不存在: " + id));
    }

    private String datasetIdOf(String datasetVersionId) {
        return requireDatasetVersion(datasetVersionId).datasetId();
    }

    private Map<String, Object> datasetView(EvalDataset dataset) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", dataset.id());
        view.put("code", dataset.code());
        view.put("displayName", dataset.displayName());
        view.put("description", dataset.description());
        view.put("currentVersionId", dataset.currentVersionId());
        view.put("status", dataset.status());
        view.put("caseCount", repository.countCases(dataset.currentVersionId() == null ? "" : dataset.currentVersionId()));
        view.put("createdBy", dataset.createdBy());
        view.put("createdAt", dataset.createdAt());
        view.put("updatedAt", dataset.updatedAt());
        return view;
    }

    private Map<String, Object> versionView(EvalDatasetVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id());
        view.put("datasetId", version.datasetId());
        view.put("versionNo", version.versionNo());
        view.put("status", version.status());
        view.put("description", version.description());
        view.put("caseCount", repository.countCases(version.id()));
        view.put("createdAt", version.createdAt());
        return view;
    }

    private Map<String, Object> caseView(EvalCase evalCase) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", evalCase.id());
        view.put("datasetVersionId", evalCase.datasetVersionId());
        view.put("caseKey", evalCase.caseKey());
        view.put("category", evalCase.category());
        view.put("input", mask(evalCase.input()));
        view.put("expected", mask(evalCase.expected()));
        view.put("tags", evalCase.tags());
        view.put("source", evalCase.source());
        view.put("traceId", evalCase.traceId());
        view.put("createdBy", evalCase.createdBy());
        view.put("createdAt", evalCase.createdAt());
        return view;
    }

    private Map<String, Object> evaluatorView(EvalEvaluator evaluator) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", evaluator.id());
        view.put("code", evaluator.code());
        view.put("displayName", evaluator.displayName());
        view.put("evaluatorType", evaluator.evaluatorType());
        view.put("description", evaluator.description());
        view.put("status", evaluator.status());
        view.put("currentVersionId", evaluator.currentVersionId());
        view.put("versions", repository.evaluatorVersions(evaluator.id()).stream()
                .map(this::evaluatorVersionView).toList());
        view.put("createdAt", evaluator.createdAt());
        return view;
    }

    private Map<String, Object> evaluatorVersionView(EvalEvaluatorVersion version) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", version.id());
        view.put("evaluatorId", version.evaluatorId());
        view.put("versionNo", version.versionNo());
        view.put("status", version.status());
        view.put("config", mask(version.config()));
        view.put("createdAt", version.createdAt());
        return view;
    }

    private Map<String, Object> experimentView(EvalExperiment experiment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", experiment.id());
        view.put("code", experiment.code());
        view.put("displayName", experiment.displayName());
        view.put("datasetId", experiment.datasetId());
        view.put("datasetVersionId", experiment.datasetVersionId());
        view.put("agentApplicationId", experiment.agentApplicationId());
        view.put("agentVersionId", experiment.agentVersionId());
        view.put("evaluatorVersionIds", experiment.evaluatorVersionIds());
        view.put("status", experiment.status());
        view.put("totalCases", experiment.totalCases());
        view.put("completedCases", experiment.completedCases());
        view.put("passedCases", experiment.passedCases());
        view.put("failedCases", experiment.failedCases());
        view.put("errorCases", experiment.errorCases());
        view.put("costMicros", experiment.costMicros());
        view.put("thresholdPassRate", experiment.thresholdPassRate());
        view.put("passRate", experiment.passRate());
        view.put("startedAt", experiment.startedAt());
        view.put("finishedAt", experiment.finishedAt());
        view.put("createdBy", experiment.createdBy());
        view.put("createdAt", experiment.createdAt());
        view.put("updatedAt", experiment.updatedAt());
        return view;
    }

    private Map<String, Object> runView(EvalExperimentRun run) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", run.id());
        view.put("experimentId", run.experimentId());
        view.put("caseId", run.caseId());
        view.put("caseKey", run.caseKey());
        view.put("status", run.status());
        view.put("passed", run.passed());
        view.put("score", run.score());
        view.put("outputSummary", run.outputSummary());
        view.put("evaluatorResults", run.evaluatorResults());
        view.put("errorCode", run.errorCode());
        view.put("tokensUsed", run.tokensUsed());
        view.put("costMicros", run.costMicros());
        view.put("startedAt", run.startedAt());
        view.put("finishedAt", run.finishedAt());
        return view;
    }

    private static String runKey(String datasetVersionId, String agentVersionId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((datasetVersionId + "|" + agentVersionId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String maskText(String value) {
        if (value == null) {
            return null;
        }
        String masked = SensitiveMasker.maskText(value);
        return masked.length() > 3000 ? masked.substring(0, 3000) : masked;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mask(Map<String, Object> value) {
        Object masked = SensitiveMasker.maskValue(value == null ? Map.of() : value);
        return masked instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int versionNumber(String versionCode) {
        try {
            return Integer.parseInt(versionCode.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException exception) {
            return 1;
        }
    }

    private static String required(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return String.valueOf(value).trim();
    }

    private static String optional(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String defaulted(Map<String, Object> input, String key, String defaultValue) {
        String value = optional(input, key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }
}
