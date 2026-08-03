package com.manzhushaka.agent.controlplane.evaluation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Process-local evaluation store used when the runtime is not backed by MySQL. */
@Repository
@Profile("!runtime-jdbc")
public class InMemoryEvaluationRepository implements EvaluationRepository {
    private final Map<String, EvalDataset> datasets = new ConcurrentHashMap<>();
    private final Map<String, EvalDatasetVersion> datasetVersions = new ConcurrentHashMap<>();
    private final Map<String, EvalCase> cases = new ConcurrentHashMap<>();
    private final Map<String, EvalEvaluator> evaluators = new ConcurrentHashMap<>();
    private final Map<String, EvalEvaluatorVersion> evaluatorVersions = new ConcurrentHashMap<>();
    private final Map<String, EvalExperiment> experiments = new ConcurrentHashMap<>();
    private final Map<String, EvalExperimentRun> runs = new ConcurrentHashMap<>();

    @Override
    public EvalDataset saveDataset(EvalDataset dataset) {
        datasets.put(dataset.id(), dataset);
        return dataset;
    }

    @Override
    public EvalPage<EvalDataset> datasets(String keyword, int page, int size) {
        List<EvalDataset> filtered = datasets.values().stream()
                .filter(dataset -> match(dataset.code(), keyword) || match(dataset.displayName(), keyword)
                        || match(dataset.description(), keyword))
                .sorted(Comparator.comparing(EvalDataset::updatedAt).reversed())
                .toList();
        return page(filtered, page, size);
    }

    @Override
    public Optional<EvalDataset> dataset(String id) {
        return Optional.ofNullable(datasets.get(id));
    }

    @Override
    public Optional<EvalDataset> datasetByCode(String code) {
        return datasets.values().stream().filter(dataset -> dataset.code().equals(code)).findFirst();
    }

    @Override
    public EvalDatasetVersion saveDatasetVersion(EvalDatasetVersion version) {
        datasetVersions.put(version.id(), version);
        return version;
    }

    @Override
    public List<EvalDatasetVersion> datasetVersions(String datasetId) {
        return datasetVersions.values().stream()
                .filter(version -> version.datasetId().equals(datasetId))
                .sorted(Comparator.comparingInt(EvalDatasetVersion::versionNo))
                .toList();
    }

    @Override
    public Optional<EvalDatasetVersion> datasetVersion(String id) {
        return Optional.ofNullable(datasetVersions.get(id));
    }

    @Override
    public Optional<EvalDatasetVersion> datasetVersionByNumber(String datasetId, int versionNo) {
        return datasetVersions.values().stream()
                .filter(version -> version.datasetId().equals(datasetId) && version.versionNo() == versionNo)
                .findFirst();
    }

    @Override
    public EvalCase saveCase(EvalCase evalCase) {
        cases.put(evalCase.id(), evalCase);
        return evalCase;
    }

    @Override
    public EvalPage<EvalCase> cases(String datasetVersionId, String category, String keyword, int page, int size) {
        List<EvalCase> filtered = cases.values().stream()
                .filter(evalCase -> evalCase.datasetVersionId().equals(datasetVersionId))
                .filter(evalCase -> category == null || category.isBlank()
                        || evalCase.category().equalsIgnoreCase(category))
                .filter(evalCase -> match(evalCase.caseKey(), keyword) || match(evalCase.category(), keyword)
                        || match(evalCase.source(), keyword))
                .sorted(Comparator.comparing(EvalCase::createdAt).reversed())
                .toList();
        return page(filtered, page, size);
    }

    @Override
    public List<EvalCase> casesByDatasetVersion(String datasetVersionId) {
        return cases.values().stream()
                .filter(evalCase -> evalCase.datasetVersionId().equals(datasetVersionId))
                .sorted(Comparator.comparing(EvalCase::caseKey))
                .toList();
    }

    @Override
    public long countCases(String datasetVersionId) {
        return cases.values().stream()
                .filter(evalCase -> evalCase.datasetVersionId().equals(datasetVersionId))
                .count();
    }

    @Override
    public Optional<EvalCase> caseById(String id) {
        return Optional.ofNullable(cases.get(id));
    }

    @Override
    public Optional<EvalCase> caseByKey(String datasetVersionId, String caseKey) {
        return cases.values().stream()
                .filter(evalCase -> evalCase.datasetVersionId().equals(datasetVersionId)
                        && evalCase.caseKey().equals(caseKey))
                .findFirst();
    }

    @Override
    public List<EvalCase> casesByTrace(String traceId) {
        return cases.values().stream()
                .filter(evalCase -> evalCase.traceId() != null && evalCase.traceId().equals(traceId))
                .toList();
    }

    @Override
    public EvalEvaluator saveEvaluator(EvalEvaluator evaluator) {
        evaluators.put(evaluator.id(), evaluator);
        return evaluator;
    }

    @Override
    public EvalPage<EvalEvaluator> evaluators(String keyword, int page, int size) {
        List<EvalEvaluator> filtered = evaluators.values().stream()
                .filter(evaluator -> match(evaluator.code(), keyword) || match(evaluator.displayName(), keyword)
                        || match(evaluator.evaluatorType(), keyword))
                .sorted(Comparator.comparing(EvalEvaluator::updatedAt).reversed())
                .toList();
        return page(filtered, page, size);
    }

    @Override
    public Optional<EvalEvaluator> evaluator(String id) {
        return Optional.ofNullable(evaluators.get(id));
    }

    @Override
    public Optional<EvalEvaluator> evaluatorByCode(String code) {
        return evaluators.values().stream().filter(evaluator -> evaluator.code().equals(code)).findFirst();
    }

    @Override
    public EvalEvaluatorVersion saveEvaluatorVersion(EvalEvaluatorVersion version) {
        evaluatorVersions.put(version.id(), version);
        return version;
    }

    @Override
    public List<EvalEvaluatorVersion> evaluatorVersions(String evaluatorId) {
        return evaluatorVersions.values().stream()
                .filter(version -> version.evaluatorId().equals(evaluatorId))
                .sorted(Comparator.comparingInt(EvalEvaluatorVersion::versionNo))
                .toList();
    }

    @Override
    public Optional<EvalEvaluatorVersion> evaluatorVersion(String id) {
        return Optional.ofNullable(evaluatorVersions.get(id));
    }

    @Override
    public EvalExperiment saveExperiment(EvalExperiment experiment) {
        experiments.put(experiment.id(), experiment);
        return experiment;
    }

    @Override
    public EvalPage<EvalExperiment> experiments(String keyword, String status, int page, int size) {
        List<EvalExperiment> filtered = experiments.values().stream()
                .filter(experiment -> status == null || status.isBlank()
                        || experiment.status().equalsIgnoreCase(status))
                .filter(experiment -> match(experiment.code(), keyword) || match(experiment.displayName(), keyword))
                .sorted(Comparator.comparing(EvalExperiment::updatedAt).reversed())
                .toList();
        return page(filtered, page, size);
    }

    @Override
    public Optional<EvalExperiment> experiment(String id) {
        return Optional.ofNullable(experiments.get(id));
    }

    @Override
    public Optional<EvalExperiment> experimentByCode(String code) {
        return experiments.values().stream().filter(experiment -> experiment.code().equals(code)).findFirst();
    }

    @Override
    public synchronized Optional<EvalExperiment> claimExperiment(String owner, Instant now, Instant leaseUntil) {
        Optional<EvalExperiment> candidate = experiments.values().stream()
                .filter(experiment -> "RUNNING".equals(experiment.status()))
                .filter(experiment -> experiment.claimOwner() == null
                        || experiment.claimLeaseUntil() == null
                        || experiment.claimLeaseUntil().isBefore(now))
                .sorted(Comparator.comparing(EvalExperiment::updatedAt))
                .findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        EvalExperiment claimed = candidate.get();
        EvalExperiment updated = new EvalExperiment(
                claimed.id(), claimed.code(), claimed.displayName(), claimed.datasetId(),
                claimed.datasetVersionId(), claimed.agentApplicationId(), claimed.agentVersionId(),
                claimed.evaluatorVersionIds(), claimed.status(), claimed.runKey(), claimed.totalCases(),
                claimed.completedCases(), claimed.passedCases(), claimed.failedCases(), claimed.errorCases(),
                claimed.costMicros(), claimed.thresholdPassRate(), claimed.passRate(),
                owner, leaseUntil, claimed.startedAt(), claimed.finishedAt(),
                claimed.createdBy(), claimed.createdAt(), Instant.now()
        );
        experiments.put(claimed.id(), updated);
        return Optional.of(updated);
    }

    @Override
    public void releaseExperiment(String experimentId) {
        experiments.computeIfPresent(experimentId, (id, experiment) -> new EvalExperiment(
                experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                experiment.datasetVersionId(), experiment.agentApplicationId(), experiment.agentVersionId(),
                experiment.evaluatorVersionIds(), experiment.status(), experiment.runKey(), experiment.totalCases(),
                experiment.completedCases(), experiment.passedCases(), experiment.failedCases(), experiment.errorCases(),
                experiment.costMicros(), experiment.thresholdPassRate(), experiment.passRate(),
                null, null, experiment.startedAt(), experiment.finishedAt(),
                experiment.createdBy(), experiment.createdAt(), Instant.now()
        ));
    }

    @Override
    public EvalExperimentRun saveRun(EvalExperimentRun run) {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public Optional<EvalExperimentRun> run(String experimentId, String caseId) {
        return runs.values().stream()
                .filter(run -> run.experimentId().equals(experimentId) && run.caseId().equals(caseId))
                .findFirst();
    }

    @Override
    public EvalPage<EvalExperimentRun> runs(String experimentId, int page, int size) {
        List<EvalExperimentRun> filtered = runs.values().stream()
                .filter(run -> run.experimentId().equals(experimentId))
                .sorted(Comparator.comparing(EvalExperimentRun::createdAt).reversed())
                .toList();
        return page(filtered, page, size);
    }

    @Override
    public List<EvalExperimentRun> runsByExperiment(String experimentId) {
        return runs.values().stream()
                .filter(run -> run.experimentId().equals(experimentId))
                .sorted(Comparator.comparing(EvalExperimentRun::createdAt))
                .toList();
    }

    private static <T> EvalPage<T> page(List<T> values, int requestedPage, int requestedSize) {
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        long offset = (long) (page - 1) * size;
        if (offset >= values.size()) {
            return new EvalPage<>(List.of(), values.size());
        }
        int toIndex = (int) Math.min(values.size(), offset + size);
        return new EvalPage<>(values.subList((int) offset, toIndex), values.size());
    }

    private static boolean match(String value, String keyword) {
        return keyword == null || keyword.isBlank()
                || (value != null && value.toLowerCase(Locale.ROOT).contains(keyword.trim().toLowerCase(Locale.ROOT)));
    }
}
