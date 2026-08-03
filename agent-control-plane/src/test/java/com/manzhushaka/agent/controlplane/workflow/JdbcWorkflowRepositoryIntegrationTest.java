package com.manzhushaka.agent.controlplane.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.ControlPlaneAudit;
import com.manzhushaka.agent.controlplane.JdbcControlPlaneRepository;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC-only contract test for the workflow repository against a fresh MySQL 8.4 schema:
 * definition/version persistence, immutable resource bindings, version uniqueness and
 * concurrent publish where exactly one winner can own the same version number.
 */
class JdbcWorkflowRepositoryIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("workflow_repo_it")
            .withUsername("workflow")
            .withPassword("workflow");

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcTemplate jdbc;
    private JdbcWorkflowRepository repository;

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
        repository = new JdbcWorkflowRepository(jdbc, JSON);
    }

    @Test
    void lifecycleSurvivesRepositoryRestartAndKeepsBindings() {
        Instant now = Instant.now();
        repository.saveWorkflow(new WorkflowDefinition(
                "wfo_1", "wf-demo", "演示", "demo", "DRAFT", null, "admin", now, now));
        repository.saveVersion(new WorkflowVersion(
                "wfv_1", "wfo_1", 1, "DRAFT", "1.0", "{\"schemaVersion\":\"1.0\"}",
                Map.of("modelVersionId", "m1", "promptVersionId", "p1",
                        "toolVersionIds", List.of("t1", "t2")),
                "v1", "admin", null, now, now));
        repository.saveVersion(new WorkflowVersion(
                "wfv_2", "wfo_1", 2, "PUBLISHED", "1.0", "{\"schemaVersion\":\"1.0\"}",
                Map.of("knowledgeBaseVersionId", "kbv_1"), "v2", "admin", now, now, now));

        JdbcWorkflowRepository reloaded = new JdbcWorkflowRepository(jdbc, JSON);
        assertEquals("wf-demo", reloaded.workflow("wfo_1").orElseThrow().code());
        assertEquals("wf-demo", reloaded.workflowByCode("wf-demo").orElseThrow().code());
        WorkflowVersion published = reloaded.version("wfv_2").orElseThrow();
        assertEquals(2, published.versionNo());
        assertEquals("PUBLISHED", published.status());
        assertEquals(Map.of("knowledgeBaseVersionId", "kbv_1"), published.resourceBindings());
        assertEquals(List.of("t1", "t2"), reloaded.version("wfv_1").orElseThrow()
                .resourceBindings().get("toolVersionIds"));
        assertEquals(2, reloaded.versions("wfo_1").size());
        assertEquals(3, reloaded.nextVersionNo("wfo_1"));
    }

    @Test
    void duplicateVersionNumberIsRejectedByUniqueConstraint() {
        Instant now = Instant.now();
        repository.saveWorkflow(new WorkflowDefinition(
                "wfo_2", "wf-dup", "重复", null, "DRAFT", null, "admin", now, now));
        repository.saveVersion(new WorkflowVersion(
                "wfv_3", "wfo_2", 1, "DRAFT", "1.0", "{}", Map.of(), null, "admin", null, now, now));
        assertThrows(DuplicateKeyException.class, () -> repository.saveVersion(
                new WorkflowVersion("wfv_4", "wfo_2", 1, "DRAFT", "1.0", "{}", Map.of(),
                        null, "admin", null, now, now)));
    }

    @Test
    void concurrentPublishAllowsExactlyOneWinner() throws Exception {
        Instant now = Instant.now();
        repository.saveWorkflow(new WorkflowDefinition(
                "wfo_3", "wf-concurrent", "并发", null, "DRAFT", null, "admin", now, now));
        WorkflowVersion draft = new WorkflowVersion(
                "wfv_5", "wfo_3", 1, "DRAFT", "1.0", "{}", Map.of(), null, "admin", null, now, now);
        repository.saveVersion(draft);

        AtomicInteger winners = new AtomicInteger();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        int updated = jdbc.update(
                                "UPDATE agent_workflow_version SET status='PUBLISHED', published_at=?, updated_at=? "
                                        + "WHERE id=? AND status='DRAFT'",
                                java.sql.Timestamp.from(Instant.now()),
                                java.sql.Timestamp.from(Instant.now()), "wfv_5");
                        if (updated == 1) {
                            winners.incrementAndGet();
                        }
                    } catch (Exception ignored) {
                        // unique/state races are expected; only conditional update counts
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        assertEquals(1, winners.get());
        assertEquals("PUBLISHED", repository.version("wfv_5").orElseThrow().status());
        assertTrue(repository.version("wfv_5").orElseThrow().publishedAt() != null);
    }

    @Test
    void searchAndPagingReturnStableOrder() {
        Instant now = Instant.now();
        for (int index = 1; index <= 3; index++) {
            repository.saveWorkflow(new WorkflowDefinition(
                    "wfo_s" + index, "wf-s" + index, "搜索" + index, null, "DRAFT", null,
                    "admin", now.plusSeconds(index), now.plusSeconds(index)));
        }
        assertEquals(3, repository.countWorkflows(null));
        assertEquals(3, repository.countWorkflows("wf-s"));
        assertEquals(2, repository.workflows("wf-s", 1, 2).size());
        assertEquals(1, repository.workflows("wf-s", 2, 2).size());
    }
    @Test
    void workflowAuditWithPrefixedResourceIdFitsWidenedColumn() {
        // Workflow 资源 id 为 "wfo_/wfv_ + UUID"（40 字符），M7 前审计表 resource_id 为 CHAR(36)。
        // 回归：V015 加宽后 WORKFLOW_* 审计必须可写入，且审计 id 保持 36 字符裸 UUID。
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(jdbc.getDataSource());
        JdbcControlPlaneRepository controlPlane = new JdbcControlPlaneRepository(jdbc, JSON, transactionManager);
        Instant now = Instant.now();
        String workflowId = "wfo_" + UUID.randomUUID();
        controlPlane.appendAudit(new ControlPlaneAudit(
                UUID.randomUUID().toString(), "admin", "WORKFLOW_CREATED", "WORKFLOW",
                workflowId, Map.of("code", "wf-audit"), now));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_control_plane_audit WHERE resource_type='WORKFLOW' AND resource_id=?",
                Integer.class, workflowId);
        assertEquals(1, count);
    }
}
