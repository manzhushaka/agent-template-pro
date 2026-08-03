package com.manzhushaka.agent.controlplane.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** MySQL-backed evaluation repository. Claim leases make the worker restart-recoverable. */
@Repository
@Profile("runtime-jdbc")
public class JdbcEvaluationRepository implements EvaluationRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<Map<String, Object>>> RESULT_LIST_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public JdbcEvaluationRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.json = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public EvalDataset saveDataset(EvalDataset dataset) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_dataset SET display_name=?,description_text=?,status=?,current_version_id=?,updated_at=? WHERE id=?",
                    dataset.displayName(), nullable(dataset.description()), dataset.status(),
                    nullable(dataset.currentVersionId()), Timestamp.from(Instant.now()), dataset.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_dataset(id,code,display_name,description_text,current_version_id,status,created_by,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?)",
                        dataset.id(), dataset.code(), dataset.displayName(), nullable(dataset.description()),
                        nullable(dataset.currentVersionId()), dataset.status(), dataset.createdBy(),
                        time(dataset.createdAt()), time(dataset.updatedAt()));
            }
        });
        return dataset;
    }

    @Override
    public EvalPage<EvalDataset> datasets(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendKeyword(where, args, keyword, List.of("code", "display_name", "description_text"));
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_eval_dataset " + where, Long.class, args.toArray());
        if (total == 0) {
            return new EvalPage<>(List.of(), 0);
        }
        List<EvalDataset> items = jdbc.query(
                "SELECT * FROM agent_eval_dataset " + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> dataset(rs), appendPaging(args, page, size));
        return new EvalPage<>(items, total);
    }

    @Override
    public Optional<EvalDataset> dataset(String id) {
        return jdbc.query("SELECT * FROM agent_eval_dataset WHERE id=?", (rs, row) -> dataset(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalDataset> datasetByCode(String code) {
        return jdbc.query("SELECT * FROM agent_eval_dataset WHERE code=?", (rs, row) -> dataset(rs), code)
                .stream().findFirst();
    }

    @Override
    public EvalDatasetVersion saveDatasetVersion(EvalDatasetVersion version) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_dataset_version SET status=?,description_text=?,updated_at=? WHERE id=?",
                    version.status(), nullable(version.description()), Timestamp.from(Instant.now()), version.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_dataset_version(id,dataset_id,version_no,status,description_text,created_by,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?)",
                        version.id(), version.datasetId(), version.versionNo(), version.status(),
                        nullable(version.description()), version.createdBy(),
                        time(version.createdAt()), time(version.updatedAt()));
            }
        });
        return version;
    }

    @Override
    public List<EvalDatasetVersion> datasetVersions(String datasetId) {
        return jdbc.query(
                "SELECT * FROM agent_eval_dataset_version WHERE dataset_id=? ORDER BY version_no ASC",
                (rs, row) -> datasetVersion(rs), datasetId);
    }

    @Override
    public Optional<EvalDatasetVersion> datasetVersion(String id) {
        return jdbc.query("SELECT * FROM agent_eval_dataset_version WHERE id=?", (rs, row) -> datasetVersion(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalDatasetVersion> datasetVersionByNumber(String datasetId, int versionNo) {
        return jdbc.query(
                "SELECT * FROM agent_eval_dataset_version WHERE dataset_id=? AND version_no=?",
                (rs, row) -> datasetVersion(rs), datasetId, versionNo).stream().findFirst();
    }

    @Override
    public EvalCase saveCase(EvalCase evalCase) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_case SET category=?,input_json=?,expected_json=?,tags_json=?,source=?,trace_id=? WHERE id=?",
                    evalCase.category(), json(evalCase.input()), json(evalCase.expected()), json(evalCase.tags()),
                    evalCase.source(), nullable(evalCase.traceId()), evalCase.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_case(id,dataset_id,dataset_version_id,case_key,category,input_json,expected_json,tags_json,source,trace_id,created_by,created_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        evalCase.id(), evalCase.datasetId(), evalCase.datasetVersionId(), evalCase.caseKey(),
                        evalCase.category(), json(evalCase.input()), json(evalCase.expected()),
                        json(evalCase.tags()), evalCase.source(), nullable(evalCase.traceId()),
                        evalCase.createdBy(), time(evalCase.createdAt()));
            }
        });
        return evalCase;
    }

    @Override
    public EvalPage<EvalCase> cases(String datasetVersionId, String category, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE dataset_version_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(datasetVersionId);
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ? ");
            args.add(category);
        }
        appendKeyword(where, args, keyword, List.of("case_key", "category", "source"));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM agent_eval_case " + where, Long.class, args.toArray());
        if (total == 0) {
            return new EvalPage<>(List.of(), 0);
        }
        List<EvalCase> items = jdbc.query(
                "SELECT * FROM agent_eval_case " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> evalCase(rs), appendPaging(args, page, size));
        return new EvalPage<>(items, total);
    }

    @Override
    public List<EvalCase> casesByDatasetVersion(String datasetVersionId) {
        return jdbc.query(
                "SELECT * FROM agent_eval_case WHERE dataset_version_id=? ORDER BY case_key ASC",
                (rs, row) -> evalCase(rs), datasetVersionId);
    }

    @Override
    public long countCases(String datasetVersionId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_eval_case WHERE dataset_version_id=?", Long.class, datasetVersionId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<EvalCase> caseById(String id) {
        return jdbc.query("SELECT * FROM agent_eval_case WHERE id=?", (rs, row) -> evalCase(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalCase> caseByKey(String datasetVersionId, String caseKey) {
        return jdbc.query(
                "SELECT * FROM agent_eval_case WHERE dataset_version_id=? AND case_key=?",
                (rs, row) -> evalCase(rs), datasetVersionId, caseKey).stream().findFirst();
    }

    @Override
    public List<EvalCase> casesByTrace(String traceId) {
        return jdbc.query("SELECT * FROM agent_eval_case WHERE trace_id=?", (rs, row) -> evalCase(rs), traceId);
    }

    @Override
    public EvalEvaluator saveEvaluator(EvalEvaluator evaluator) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_evaluator SET display_name=?,evaluator_type=?,description_text=?,status=?,current_version_id=?,updated_at=? WHERE id=?",
                    evaluator.displayName(), evaluator.evaluatorType(), nullable(evaluator.description()),
                    evaluator.status(), nullable(evaluator.currentVersionId()), Timestamp.from(Instant.now()),
                    evaluator.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_evaluator(id,code,display_name,evaluator_type,description_text,status,current_version_id,created_by,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                        evaluator.id(), evaluator.code(), evaluator.displayName(), evaluator.evaluatorType(),
                        nullable(evaluator.description()), evaluator.status(), nullable(evaluator.currentVersionId()),
                        evaluator.createdBy(), time(evaluator.createdAt()), time(evaluator.updatedAt()));
            }
        });
        return evaluator;
    }

    @Override
    public EvalPage<EvalEvaluator> evaluators(String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        appendKeyword(where, args, keyword, List.of("code", "display_name", "evaluator_type"));
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_eval_evaluator " + where, Long.class, args.toArray());
        if (total == 0) {
            return new EvalPage<>(List.of(), 0);
        }
        List<EvalEvaluator> items = jdbc.query(
                "SELECT * FROM agent_eval_evaluator " + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> evaluator(rs), appendPaging(args, page, size));
        return new EvalPage<>(items, total);
    }

    @Override
    public Optional<EvalEvaluator> evaluator(String id) {
        return jdbc.query("SELECT * FROM agent_eval_evaluator WHERE id=?", (rs, row) -> evaluator(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalEvaluator> evaluatorByCode(String code) {
        return jdbc.query("SELECT * FROM agent_eval_evaluator WHERE code=?", (rs, row) -> evaluator(rs), code)
                .stream().findFirst();
    }

    @Override
    public EvalEvaluatorVersion saveEvaluatorVersion(EvalEvaluatorVersion version) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_evaluator_version SET status=?,config_json=? WHERE id=?",
                    version.status(), json(version.config()), version.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_evaluator_version(id,evaluator_id,version_no,status,config_json,created_by,created_at) "
                                + "VALUES(?,?,?,?,?,?,?)",
                        version.id(), version.evaluatorId(), version.versionNo(), version.status(),
                        json(version.config()), version.createdBy(), time(version.createdAt()));
            }
        });
        return version;
    }

    @Override
    public List<EvalEvaluatorVersion> evaluatorVersions(String evaluatorId) {
        return jdbc.query(
                "SELECT * FROM agent_eval_evaluator_version WHERE evaluator_id=? ORDER BY version_no ASC",
                (rs, row) -> evaluatorVersion(rs), evaluatorId);
    }

    @Override
    public Optional<EvalEvaluatorVersion> evaluatorVersion(String id) {
        return jdbc.query("SELECT * FROM agent_eval_evaluator_version WHERE id=?", (rs, row) -> evaluatorVersion(rs), id)
                .stream().findFirst();
    }

    @Override
    public EvalExperiment saveExperiment(EvalExperiment experiment) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_experiment SET display_name=?,status=?,total_cases=?,completed_cases=?,passed_cases=?,"
                            + "failed_cases=?,error_cases=?,cost_micros=?,pass_rate=?,claim_owner=?,claim_lease_until=?,"
                            + "started_at=?,finished_at=?,updated_at=? WHERE id=?",
                    experiment.displayName(), experiment.status(), experiment.totalCases(),
                    experiment.completedCases(), experiment.passedCases(), experiment.failedCases(),
                    experiment.errorCases(), experiment.costMicros(), nullable(experiment.passRate()),
                    nullable(experiment.claimOwner()), nullableTime(experiment.claimLeaseUntil()),
                    nullableTime(experiment.startedAt()), nullableTime(experiment.finishedAt()),
                    Timestamp.from(Instant.now()), experiment.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_experiment(id,code,display_name,dataset_id,dataset_version_id,agent_application_id,"
                                + "agent_version_id,evaluator_version_ids_json,status,run_key,total_cases,completed_cases,passed_cases,"
                                + "failed_cases,error_cases,cost_micros,threshold_pass_rate,pass_rate,claim_owner,claim_lease_until,"
                                + "started_at,finished_at,created_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        experiment.id(), experiment.code(), experiment.displayName(), experiment.datasetId(),
                        experiment.datasetVersionId(), nullable(experiment.agentApplicationId()),
                        experiment.agentVersionId(), json(experiment.evaluatorVersionIds()), experiment.status(),
                        experiment.runKey(), experiment.totalCases(), experiment.completedCases(),
                        experiment.passedCases(), experiment.failedCases(), experiment.errorCases(),
                        experiment.costMicros(), nullable(experiment.thresholdPassRate()),
                        nullable(experiment.passRate()), nullable(experiment.claimOwner()),
                        nullableTime(experiment.claimLeaseUntil()), nullableTime(experiment.startedAt()),
                        nullableTime(experiment.finishedAt()), experiment.createdBy(),
                        time(experiment.createdAt()), time(experiment.updatedAt()));
            }
        });
        return experiment;
    }

    @Override
    public EvalPage<EvalExperiment> experiments(String keyword, String status, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ? ");
            args.add(status.toUpperCase(Locale.ROOT));
        }
        appendKeyword(where, args, keyword, List.of("code", "display_name"));
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_eval_experiment " + where, Long.class, args.toArray());
        if (total == 0) {
            return new EvalPage<>(List.of(), 0);
        }
        List<EvalExperiment> items = jdbc.query(
                "SELECT * FROM agent_eval_experiment " + where + " ORDER BY updated_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> experiment(rs), appendPaging(args, page, size));
        return new EvalPage<>(items, total);
    }

    @Override
    public Optional<EvalExperiment> experiment(String id) {
        return jdbc.query("SELECT * FROM agent_eval_experiment WHERE id=?", (rs, row) -> experiment(rs), id)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalExperiment> experimentByCode(String code) {
        return jdbc.query("SELECT * FROM agent_eval_experiment WHERE code=?", (rs, row) -> experiment(rs), code)
                .stream().findFirst();
    }

    @Override
    public Optional<EvalExperiment> claimExperiment(String owner, Instant now, Instant leaseUntil) {
        return tx.execute(status -> {
            List<String> candidates = jdbc.query(
                    "SELECT id FROM agent_eval_experiment WHERE status='RUNNING' "
                            + "AND (claim_owner IS NULL OR claim_lease_until IS NULL OR claim_lease_until < ?) "
                            + "ORDER BY updated_at ASC LIMIT 1 FOR UPDATE SKIP LOCKED",
                    (rs, row) -> rs.getString("id"), Timestamp.from(now));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            String id = candidates.getFirst();
            jdbc.update(
                    "UPDATE agent_eval_experiment SET claim_owner=?, claim_lease_until=?, updated_at=? WHERE id=?",
                    owner, Timestamp.from(leaseUntil), Timestamp.from(Instant.now()), id);
            return experiment(id);
        });
    }

    @Override
    public void releaseExperiment(String experimentId) {
        jdbc.update("UPDATE agent_eval_experiment SET claim_owner=NULL,claim_lease_until=NULL,updated_at=? WHERE id=?",
                Timestamp.from(Instant.now()), experimentId);
    }

    @Override
    public EvalExperimentRun saveRun(EvalExperimentRun run) {
        tx.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    "UPDATE agent_eval_experiment_run SET status=?,passed=?,score=?,output_summary=?,evaluator_results_json=?,"
                            + "error_code=?,tokens_used=?,cost_micros=?,started_at=?,finished_at=?,updated_at=? WHERE id=?",
                    run.status(), run.passed(), nullable(run.score()), nullable(run.outputSummary()),
                    json(run.evaluatorResults()), nullable(run.errorCode()), run.tokensUsed(), run.costMicros(),
                    nullableTime(run.startedAt()), nullableTime(run.finishedAt()), Timestamp.from(Instant.now()),
                    run.id());
            if (updated == 0) {
                jdbc.update(
                        "INSERT INTO agent_eval_experiment_run(id,experiment_id,case_id,case_key,status,passed,score,output_summary,"
                                + "evaluator_results_json,error_code,tokens_used,cost_micros,started_at,finished_at,created_at,updated_at) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        run.id(), run.experimentId(), run.caseId(), run.caseKey(), run.status(), run.passed(),
                        nullable(run.score()), nullable(run.outputSummary()), json(run.evaluatorResults()),
                        nullable(run.errorCode()), run.tokensUsed(), run.costMicros(),
                        nullableTime(run.startedAt()), nullableTime(run.finishedAt()),
                        time(run.createdAt()), time(run.updatedAt()));
            }
        });
        return run;
    }

    @Override
    public Optional<EvalExperimentRun> run(String experimentId, String caseId) {
        return jdbc.query(
                "SELECT * FROM agent_eval_experiment_run WHERE experiment_id=? AND case_id=?",
                (rs, row) -> experimentRun(rs), experimentId, caseId).stream().findFirst();
    }

    @Override
    public EvalPage<EvalExperimentRun> runs(String experimentId, int page, int size) {
        long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_eval_experiment_run WHERE experiment_id=?", Long.class, experimentId);
        if (total == 0) {
            return new EvalPage<>(List.of(), 0);
        }
        List<EvalExperimentRun> items = jdbc.query(
                "SELECT * FROM agent_eval_experiment_run WHERE experiment_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> experimentRun(rs), appendPaging(new ArrayList<>(List.of(experimentId)), page, size));
        return new EvalPage<>(items, total);
    }

    @Override
    public List<EvalExperimentRun> runsByExperiment(String experimentId) {
        return jdbc.query(
                "SELECT * FROM agent_eval_experiment_run WHERE experiment_id=? ORDER BY created_at ASC",
                (rs, row) -> experimentRun(rs), experimentId);
    }

    private EvalDataset dataset(ResultSet rs) throws SQLException {
        return new EvalDataset(rs.getString("id"), rs.getString("code"), rs.getString("display_name"),
                rs.getString("description_text"), rs.getString("current_version_id"), rs.getString("status"),
                rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private EvalDatasetVersion datasetVersion(ResultSet rs) throws SQLException {
        return new EvalDatasetVersion(rs.getString("id"), rs.getString("dataset_id"), rs.getInt("version_no"),
                rs.getString("status"), rs.getString("description_text"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private EvalCase evalCase(ResultSet rs) throws SQLException {
        return new EvalCase(rs.getString("id"), rs.getString("dataset_id"), rs.getString("dataset_version_id"),
                rs.getString("case_key"), rs.getString("category"), map(rs.getString("input_json")),
                map(rs.getString("expected_json")), map(rs.getString("tags_json")), rs.getString("source"),
                rs.getString("trace_id"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")));
    }

    private EvalEvaluator evaluator(ResultSet rs) throws SQLException {
        return new EvalEvaluator(rs.getString("id"), rs.getString("code"), rs.getString("display_name"),
                rs.getString("evaluator_type"), rs.getString("description_text"), rs.getString("status"),
                rs.getString("current_version_id"), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private EvalEvaluatorVersion evaluatorVersion(ResultSet rs) throws SQLException {
        return new EvalEvaluatorVersion(rs.getString("id"), rs.getString("evaluator_id"), rs.getInt("version_no"),
                rs.getString("status"), map(rs.getString("config_json")), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")));
    }

    private EvalExperiment experiment(ResultSet rs) throws SQLException {
        return new EvalExperiment(rs.getString("id"), rs.getString("code"), rs.getString("display_name"),
                rs.getString("dataset_id"), rs.getString("dataset_version_id"), rs.getString("agent_application_id"),
                rs.getString("agent_version_id"), stringList(rs.getString("evaluator_version_ids_json")),
                rs.getString("status"), rs.getString("run_key"), rs.getInt("total_cases"),
                rs.getInt("completed_cases"), rs.getInt("passed_cases"), rs.getInt("failed_cases"),
                rs.getInt("error_cases"), rs.getLong("cost_micros"), decimal(rs.getBigDecimal("threshold_pass_rate")),
                decimal(rs.getBigDecimal("pass_rate")), rs.getString("claim_owner"),
                instant(rs.getTimestamp("claim_lease_until")), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), rs.getString("created_by"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private EvalExperimentRun experimentRun(ResultSet rs) throws SQLException {
        return new EvalExperimentRun(rs.getString("id"), rs.getString("experiment_id"), rs.getString("case_id"),
                rs.getString("case_key"), rs.getString("status"), bool(rs.getObject("passed")),
                decimal(rs.getBigDecimal("score")), rs.getString("output_summary"),
                resultList(rs.getString("evaluator_results_json")), rs.getString("error_code"),
                rs.getInt("tokens_used"), rs.getLong("cost_micros"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private static void appendKeyword(
            StringBuilder where,
            List<Object> args,
            String keyword,
            List<String> columns
    ) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        where.append(" AND (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                where.append(" OR ");
            }
            where.append("LOWER(").append(columns.get(i)).append(") LIKE ?");
            args.add(like);
        }
        where.append(") ");
    }

    private static Object[] appendPaging(List<Object> args, int page, int size) {
        args.add(size);
        args.add((long) (Math.max(1, page) - 1) * Math.max(1, Math.min(size, 100)));
        return args.toArray();
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化评估数据", exception);
        }
    }

    private Map<String, Object> map(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析评估 JSON", exception);
        }
    }

    private List<String> stringList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, STRING_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析评估器版本列表", exception);
        }
    }

    private List<Map<String, Object>> resultList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(value, RESULT_LIST_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析评估结果", exception);
        }
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Integer.valueOf(value.toString()).intValue() != 0;
    }

    private static Timestamp time(Instant instant) {
        return Timestamp.from(instant);
    }

    private static Timestamp nullableTime(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String nullable(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
