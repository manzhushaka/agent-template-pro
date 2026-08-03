package com.manzhushaka.agent.controlplane.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC-only contract test for the M6 evaluation repository against a fresh MySQL 8.4
 * schema: versioned datasets/cases, evaluator versions, claim leases and run persistence.
 * The full V001-V014 migration chain must apply to an empty schema.
 */
class JdbcEvaluationRepositoryIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("eval_it")
            .withUsername("eval")
            .withPassword("eval");

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcTemplate jdbc;
    private JdbcEvaluationRepository repository;

    @BeforeAll
    static void startMySql() {
        MYSQL.start();
    }

    @AfterAll
    static void stopMySql() {
        MYSQL.stop();
    }

    @BeforeEach
    void recreateSchemaFromAllMigrations() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        assertEquals(15, flyway.migrate().migrationsExecuted,
                "V001-V014 must all apply to an empty MySQL schema");

        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcEvaluationRepository(jdbc, JSON, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void datasetVersionsAndCasesRoundTripWithDatabaseUniqueKeys() {
        Instant now = Instant.now();
        EvalDataset dataset = new EvalDataset(
                "eds-1", "hotel-eval", "酒店评估", "酒店场景数据集", null, "ACTIVE", "admin", now, now);
        repository.saveDataset(dataset);
        EvalDatasetVersion version = new EvalDatasetVersion(
                "edv-1", dataset.id(), 1, "ACTIVE", "初始版本", "admin", now, now);
        repository.saveDatasetVersion(version);
        repository.saveDataset(new EvalDataset(
                dataset.id(), dataset.code(), dataset.displayName(), dataset.description(), version.id(),
                dataset.status(), dataset.createdBy(), dataset.createdAt(), now));

        assertTrue(repository.dataset("eds-1").isPresent());
        assertEquals(version.id(), repository.dataset("eds-1").orElseThrow().currentVersionId());
        assertEquals(1, repository.datasetVersions(dataset.id()).size());

        EvalCase evalCase = new EvalCase(
                "ecs-1", dataset.id(), version.id(), "case-1", "intent-route",
                Map.of("text", "查询上海酒店"), Map.of("agentCode", "hotel"), Map.of(),
                "MANUAL", null, "admin", now);
        repository.saveCase(evalCase);
        assertEquals(1, repository.cases(version.id(), null, null, 1, 10).total());
        assertTrue(repository.caseByKey(version.id(), "case-1").isPresent());

        EvalCase duplicate = new EvalCase(
                "ecs-2", dataset.id(), version.id(), "case-1", "manual",
                Map.of("text", "重复"), Map.of(), Map.of(), "MANUAL", null, "admin", now);
        assertThrows(DuplicateKeyException.class, () -> repository.saveCase(duplicate));
    }

    @Test
    void evaluatorVersionsPersistConfigAndActiveStatus() {
        Instant now = Instant.now();
        EvalEvaluator evaluator = new EvalEvaluator(
                "eev-1", "route-check", "路由检查", "INTENT_ROUTE", "检查路由", "ACTIVE",
                null, "admin", now, now);
        repository.saveEvaluator(evaluator);
        EvalEvaluatorVersion version = new EvalEvaluatorVersion(
                "eevv-1", evaluator.id(), 1, "ACTIVE", Map.of("agentCode", "hotel"), "admin", now);
        repository.saveEvaluatorVersion(version);
        repository.saveEvaluator(new EvalEvaluator(
                evaluator.id(), evaluator.code(), evaluator.displayName(), evaluator.evaluatorType(),
                evaluator.description(), evaluator.status(), version.id(),
                evaluator.createdBy(), evaluator.createdAt(), now));

        assertTrue(repository.evaluatorByCode("route-check").isPresent());
        EvalEvaluatorVersion stored = repository.evaluatorVersion("eevv-1").orElseThrow();
        assertEquals("hotel", stored.config().get("agentCode"));
        assertEquals(version.id(), repository.evaluator("eev-1").orElseThrow().currentVersionId());
    }

    @Test
    void claimLeaseExpiresAndAnotherWorkerResumes() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        repository.saveExperiment(experiment(fixture, "RUNNING", now));

        assertTrue(repository.claimExperiment("worker-a", now, now.plusSeconds(1)).isPresent());
        assertTrue(repository.claimExperiment("worker-b", now.plusMillis(100), now.plusSeconds(30)).isEmpty(),
                "租约未过期时其他 worker 不得接管");

        EvalExperiment resumed = repository.claimExperiment("worker-b", now.plusSeconds(2), now.plusSeconds(60))
                .orElseThrow();
        assertEquals("worker-b", resumed.claimOwner());

        EvalExperimentRun run = new EvalExperimentRun(
                "eru-1", fixture.experimentId(), fixture.caseId(), "case-1", "PASSED", true,
                BigDecimal.ONE, "已为您查询酒店房态。", List.of(Map.of("passed", true)),
                null, 42, 420L, now, now, now, now);
        repository.saveRun(run);
        assertTrue(repository.run(fixture.experimentId(), fixture.caseId()).isPresent());
        assertEquals(1, repository.runs(fixture.experimentId(), 1, 10).total());
        assertEquals("PASSED", repository.runsByExperiment(fixture.experimentId()).getFirst().status());
    }

    @Test
    void runRowsAreUpsertedPerCaseAndCannotBeDuplicated() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        repository.saveExperiment(experiment(fixture, "RUNNING", now));

        EvalExperimentRun pending = new EvalExperimentRun(
                "eru-pending", fixture.experimentId(), fixture.caseId(), "case-1", "PENDING",
                null, null, null, List.of(), null, 0, 0L, null, null, now, now);
        repository.saveRun(pending);

        // 恢复路径必须复用同一行（同一 id 更新），而不是再次插入。
        repository.saveRun(new EvalExperimentRun(
                "eru-pending", fixture.experimentId(), fixture.caseId(), "case-1", "PASSED", true,
                BigDecimal.ONE, "已处理", List.of(Map.of("passed", true)), null, 10, 100L,
                now, now, now, now));
        assertEquals(1, repository.runs(fixture.experimentId(), 1, 10).total());
        assertEquals("PASSED", repository.run(fixture.experimentId(), fixture.caseId())
                .orElseThrow().status());

        // 不同 id 同 (experiment_id, case_id) 必须被数据库唯一键拒绝。
        EvalExperimentRun duplicate = new EvalExperimentRun(
                "eru-other", fixture.experimentId(), fixture.caseId(), "case-1", "PENDING",
                null, null, null, List.of(), null, 0, 0L, null, null, now, now);
        assertThrows(DuplicateKeyException.class, () -> repository.saveRun(duplicate));
    }

    @Test
    void concurrentClaimersNeverBothWinTheSameExperiment() throws Exception {
        Instant now = Instant.now();
        repository.saveExperiment(experiment(fixture(), "RUNNING", now));

        int workerCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workerCount);
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            final String owner = "worker-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                repository.claimExperiment(owner, Instant.now(), Instant.now().plusSeconds(120))
                        .ifPresent(claimed -> winners.incrementAndGet());
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertEquals(1, winners.get(), "FOR UPDATE SKIP LOCKED 必须保证同一实验只有一个 worker 胜出");
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        EvalDataset dataset = new EvalDataset(
                "eds-" + now.toEpochMilli(), "ds-code", "数据集", null, null, "ACTIVE", "admin", now, now);
        repository.saveDataset(dataset);
        EvalDatasetVersion version = new EvalDatasetVersion(
                "edv-" + now.toEpochMilli(), dataset.id(), 1, "ACTIVE", null, "admin", now, now);
        repository.saveDatasetVersion(version);
        EvalCase evalCase = new EvalCase(
                "ecs-" + now.toEpochMilli(), dataset.id(), version.id(), "case-1", "manual",
                Map.of("text", "查询"), Map.of(), Map.of(), "MANUAL", null, "admin", now);
        repository.saveCase(evalCase);
        EvalEvaluator evaluator = new EvalEvaluator(
                "eev-" + now.toEpochMilli(), "code-" + now.toEpochMilli(), "评估器", "INTENT_ROUTE",
                null, "ACTIVE", null, "admin", now, now);
        repository.saveEvaluator(evaluator);
        EvalEvaluatorVersion evaluatorVersion = new EvalEvaluatorVersion(
                "eevv-" + now.toEpochMilli(), evaluator.id(), 1, "ACTIVE", Map.of(), "admin", now);
        repository.saveEvaluatorVersion(evaluatorVersion);
        return new Fixture("eex-" + now.toEpochMilli(), dataset.id(), version.id(), evalCase.id(), evaluatorVersion.id());
    }

    private static EvalExperiment experiment(Fixture fixture, String status, Instant now) {
        return new EvalExperiment(
                fixture.experimentId(), "exp-code", "实验", fixture.datasetId(), fixture.datasetVersionId(),
                "av-1", "av-1", List.of(fixture.evaluatorVersionId()), status, "run-key", 1,
                0, 0, 0, 0, 0L, null, null, null, null, now, null,
                "admin", now, now);
    }

    private record Fixture(
            String experimentId,
            String datasetId,
            String datasetVersionId,
            String caseId,
            String evaluatorVersionId
    ) {
    }
}
