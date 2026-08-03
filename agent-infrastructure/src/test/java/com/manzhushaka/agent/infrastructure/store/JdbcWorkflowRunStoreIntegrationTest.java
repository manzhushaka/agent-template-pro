package com.manzhushaka.agent.infrastructure.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.workflow.WorkflowEvent;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * JDBC-only contract test for the workflow run store against a fresh MySQL 8.4 schema.
 * Verifies run/node-run/event persistence, conditional status transitions, restart recovery
 * and the unique constraints behind the execution frontier.
 */
class JdbcWorkflowRunStoreIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("workflow_it")
            .withUsername("workflow")
            .withPassword("workflow");

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdbcTemplate jdbc;
    private JdbcWorkflowRunStore store;

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
        store = new JdbcWorkflowRunStore(jdbc, JSON);
    }

    @Test
    void runAndNodeRunRoundTripWithJsonFrontier() {
        WorkflowRun run = run("wfl_1", WorkflowRunStatus.RUNNING, Instant.now().minusSeconds(30));
        store.saveRun(run);

        WorkflowNodeRun node = new WorkflowNodeRun(
                "wfn_1", "wfl_1", "n1", WorkflowNodeType.VARIABLE_ASSIGN,
                WorkflowNodeRunStatus.SUCCEEDED, Map.of("city", "上海"), Map.of("city", "上海"),
                null, 0, null, 0, null, Instant.now(), Instant.now(), Instant.now(), Instant.now()
        );
        store.saveNodeRun(node);

        WorkflowRun loaded = store.findRun("wfl_1").orElseThrow();
        assertEquals(WorkflowRunStatus.RUNNING, loaded.status());
        assertEquals("wf-code", loaded.code());
        assertEquals(Map.of("city", "上海"), loaded.variables());
        assertEquals(List.of("start", "n1"), loaded.visitedNodeIds());
        assertEquals(List.of("start->n1"), loaded.visitedEdgeKeys());
        assertEquals(Optional.of("wfl_1"), store.findRunForVisitor("wf:admin", "wfl_1").map(WorkflowRun::id));
        assertTrue(store.findRunForVisitor("wf:other", "wfl_1").isEmpty());

        WorkflowNodeRun nodeLoaded = store.findNodeRun("wfl_1", "n1").orElseThrow();
        assertEquals(WorkflowNodeRunStatus.SUCCEEDED, nodeLoaded.status());
        assertEquals("上海", nodeLoaded.output().get("city"));
        assertEquals(1, store.nodeRuns("wfl_1").size());
    }

    @Test
    void nodeRunUpdateIsConditionalAndUniquePerNode() {
        WorkflowNodeRun node = new WorkflowNodeRun(
                "wfn_2", "wfl_2", "w", WorkflowNodeType.ACTION,
                WorkflowNodeRunStatus.WAITING_CONFIRMATION, Map.of(), Map.of(),
                "tsk_1", 1, "hash-1", 0, null,
                Instant.now(), null, Instant.now(), Instant.now()
        );
        store.saveNodeRun(node);
        WorkflowNodeRun confirmed = node.withStatus(WorkflowNodeRunStatus.RUNNING);
        store.saveNodeRun(confirmed);
        assertEquals(WorkflowNodeRunStatus.RUNNING,
                store.findNodeRun("wfl_2", "w").orElseThrow().status());

        assertThrows(DuplicateKeyException.class, () -> store.saveNodeRun(
                new WorkflowNodeRun(
                        "wfn_other", "wfl_2", "w", WorkflowNodeType.ACTION,
                        WorkflowNodeRunStatus.RUNNING, Map.of(), Map.of(),
                        null, 0, null, 0, null, Instant.now(), null, Instant.now(), Instant.now())));
    }

    @Test
    void eventSequencesAreAppendedAndQueryable() {
        WorkflowRun run = run("wfl_3", WorkflowRunStatus.RUNNING, Instant.now());
        store.saveRun(run);
        store.saveEvent(new WorkflowEvent("e1", "wfl_3", 1, "workflow.node", "n1",
                Map.of("status", "SUCCEEDED"), Instant.now()));
        store.saveEvent(new WorkflowEvent("e2", "wfl_3", 2, "workflow.status", null,
                Map.of("status", "SUCCEEDED"), Instant.now()));
        assertThrows(DuplicateKeyException.class, () -> store.saveEvent(
                new WorkflowEvent("e3", "wfl_3", 1, "workflow.node", "n1", Map.of(), Instant.now())));

        List<WorkflowEvent> afterFirst = store.events("wfl_3", 1, 10);
        assertEquals(1, afterFirst.size());
        assertEquals(2, afterFirst.getFirst().sequence());
        assertEquals(2, store.events("wfl_3", 0, 10).size());
    }

    @Test
    void conditionalStatusTransitionLetsExactlyOneConcurrentWinnerWin() throws Exception {
        WorkflowRun run = run("wfl_4", WorkflowRunStatus.RUNNING, Instant.now());
        store.saveRun(run);
        AtomicInteger winners = new AtomicInteger();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    if (store.transitionRunStatus("wfl_4", WorkflowRunStatus.RUNNING,
                            WorkflowRunStatus.PAUSED)) {
                        winners.incrementAndGet();
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
        assertEquals(WorkflowRunStatus.PAUSED, store.findRun("wfl_4").orElseThrow().status());
    }

    @Test
    void staleRunningIsRecoverableAfterRestart() {
        Instant stale = Instant.now().minusSeconds(600);
        store.saveRun(run("wfl_5", WorkflowRunStatus.RUNNING, stale));
        store.saveRun(run("wfl_6", WorkflowRunStatus.RUNNING, Instant.now()));

        JdbcWorkflowRunStore restarted = new JdbcWorkflowRunStore(jdbc, JSON);
        List<WorkflowRun> staleRuns = restarted.findStaleRunning(Instant.now(), Duration.ofMinutes(2));
        assertEquals(1, staleRuns.size());
        assertEquals("wfl_5", staleRuns.getFirst().id());
        assertTrue(restarted.transitionRunStatus("wfl_5", WorkflowRunStatus.RUNNING,
                WorkflowRunStatus.PAUSED));
        assertFalse(restarted.transitionRunStatus("wfl_5", WorkflowRunStatus.RUNNING,
                WorkflowRunStatus.PAUSED));
    }

    private static WorkflowRun run(String id, WorkflowRunStatus status, Instant updatedAt) {
        Instant now = Instant.now();
        return new WorkflowRun(
                id, "wfo_1", "wfv_1", "wf-code", "{\"schemaVersion\":\"1.0\"}", "wfg_" + id,
                status, "wf:admin", "wfr_1", Map.of("city", "上海"),
                List.of("start", "n1"), List.of("start->n1"), List.of("n1->end"),
                "n1", null, null, null, now.minusSeconds(60), null, now.minusSeconds(60), updatedAt
        );
    }
}
