package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Durable boundary for evaluation facts: datasets, cases, evaluators, experiments and runs. */
public interface EvaluationRepository {
    EvalDataset saveDataset(EvalDataset dataset);

    EvalPage<EvalDataset> datasets(String keyword, int page, int size);

    Optional<EvalDataset> dataset(String id);

    Optional<EvalDataset> datasetByCode(String code);

    EvalDatasetVersion saveDatasetVersion(EvalDatasetVersion version);

    List<EvalDatasetVersion> datasetVersions(String datasetId);

    Optional<EvalDatasetVersion> datasetVersion(String id);

    Optional<EvalDatasetVersion> datasetVersionByNumber(String datasetId, int versionNo);

    EvalCase saveCase(EvalCase evalCase);

    EvalPage<EvalCase> cases(String datasetVersionId, String category, String keyword, int page, int size);

    List<EvalCase> casesByDatasetVersion(String datasetVersionId);

    long countCases(String datasetVersionId);

    Optional<EvalCase> caseById(String id);

    Optional<EvalCase> caseByKey(String datasetVersionId, String caseKey);

    List<EvalCase> casesByTrace(String traceId);

    EvalEvaluator saveEvaluator(EvalEvaluator evaluator);

    EvalPage<EvalEvaluator> evaluators(String keyword, int page, int size);

    Optional<EvalEvaluator> evaluator(String id);

    Optional<EvalEvaluator> evaluatorByCode(String code);

    EvalEvaluatorVersion saveEvaluatorVersion(EvalEvaluatorVersion version);

    List<EvalEvaluatorVersion> evaluatorVersions(String evaluatorId);

    Optional<EvalEvaluatorVersion> evaluatorVersion(String id);

    EvalExperiment saveExperiment(EvalExperiment experiment);

    EvalPage<EvalExperiment> experiments(String keyword, String status, int page, int size);

    Optional<EvalExperiment> experiment(String id);

    Optional<EvalExperiment> experimentByCode(String code);

    /**
     * Claims a running experiment whose lease is free or expired. Only one claimer wins per
     * experiment, so progress counters are safe to update row-wise.
     */
    Optional<EvalExperiment> claimExperiment(String owner, Instant now, Instant leaseUntil);

    void releaseExperiment(String experimentId);

    EvalExperimentRun saveRun(EvalExperimentRun run);

    Optional<EvalExperimentRun> run(String experimentId, String caseId);

    EvalPage<EvalExperimentRun> runs(String experimentId, int page, int size);

    List<EvalExperimentRun> runsByExperiment(String experimentId);
}
