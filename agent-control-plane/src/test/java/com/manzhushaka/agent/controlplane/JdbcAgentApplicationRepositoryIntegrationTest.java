package com.manzhushaka.agent.controlplane;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the JDBC Agent application repository (version immutability, publish/rollback
 * transactions, concurrent publish and version creation, hash-only API keys and archive
 * gating) against a fresh MySQL 8.4 schema built from all migrations.
 */
class JdbcAgentApplicationRepositoryIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("agent_app_it")
            .withUsername("agentapp")
            .withPassword("agentapp");

    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcTemplate jdbc;
    private JdbcAgentApplicationRepository repository;
    private DataSource dataSource;

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
        assertEquals(15, flyway.migrate().migrationsExecuted, "V001-V014 must all apply to an empty MySQL schema");

        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcAgentApplicationRepository(jdbc, JSON, new DataSourceTransactionManager(dataSource));
    }

    @Test
    void publishedStateSurvivesRepositoryRestart() {
        Map<String, Object> app = saveApplication("restart-app");
        String applicationId = String.valueOf(app.get("id"));
        Map<String, Object> first = createVersion(applicationId);
        String firstId = String.valueOf(first.get("id"));
        repository.publishVersion(applicationId, firstId, null, audit("AGENT_APP_VERSION_PUBLISHED", firstId));
        repository.saveApiKey(Map.of(
                "id", UUID.randomUUID().toString(),
                "applicationId", applicationId,
                "keyHash", sha256("ag_restart_key"),
                "keyPrefix", "ag_restart",
                "status", "ACTIVE",
                "scopes", List.of("chat:completions"),
                "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()), audit("AGENT_API_KEY_CREATED", "restart-key"));

        // Simulate a process restart: a fresh repository over the same database must observe the
        // published version, publish history and hash-only key exactly as before.
        JdbcAgentApplicationRepository restarted = new JdbcAgentApplicationRepository(
                jdbc, JSON, new DataSourceTransactionManager(dataSource));

        Map<String, Object> reloaded = restarted.findApplication(applicationId).orElseThrow();
        assertEquals(firstId, reloaded.get("currentVersionId"));
        assertEquals("PUBLISHED", restarted.findVersion(firstId).orElseThrow().get("status"));
        assertEquals(1, restarted.listPublishRecords(applicationId).size());
        assertEquals("PUBLISH", restarted.listPublishRecords(applicationId).getFirst().get("action"));
        assertFalse(restarted.listApiKeys(applicationId).getFirst().containsKey("keyHash"));
        assertEquals(
                applicationId,
                restarted.findApiKeyByHash(sha256("ag_restart_key")).orElseThrow().get("applicationId"),
                "重启后仍能按 hash 命中同一应用的 API Key"
        );
    }

    @Test
    void versionLifecyclePersistsBindingsPublishRecordAndRollback() {
        Map<String, Object> app = saveApplication("customer-assistant");
        String applicationId = String.valueOf(app.get("id"));

        Map<String, Object> first = createVersion(applicationId);
        String firstId = String.valueOf(first.get("id"));
        assertEquals("DRAFT", first.get("status"));
        assertEquals(1, first.get("version"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_application_binding WHERE version_id=?", Integer.class, firstId));

        Map<String, Object> published = repository.publishVersion(
                applicationId, firstId, null, audit("AGENT_APP_VERSION_PUBLISHED", firstId));
        assertEquals("PUBLISHED", published.get("status"));
        assertNotNull(published.get("publishedAt"));
        assertEquals(firstId, repository.findApplication(applicationId).orElseThrow().get("currentVersionId"));

        Map<String, Object> persisted = repository.findVersion(firstId).orElseThrow();
        assertEquals("PUBLISHED", persisted.get("status"));
        assertEquals("model-demo", persisted.get("modelCode"));
        assertEquals("prompt-1", persisted.get("promptId"));
        assertEquals("pv-2", persisted.get("promptVersionId"));
        assertTrue(persisted.get("config") instanceof Map<?, ?>);
        List<Map<String, Object>> bindings = repository.listVersionBindings(firstId);
        assertEquals(1, bindings.size());
        assertEquals("MCP_TOOL_VERSION", bindings.getFirst().get("resourceType"));
        assertEquals("tv-3", bindings.getFirst().get("resourceVersion"));

        assertThrows(IllegalStateException.class,
                () -> repository.publishVersion(applicationId, firstId, firstId, audit("AGENT_APP_VERSION_PUBLISHED", firstId)),
                "已发布版本不能再次发布");

        Map<String, Object> second = createVersion(applicationId);
        String secondId = String.valueOf(second.get("id"));
        assertEquals(2, second.get("version"));
        repository.publishVersion(applicationId, secondId, firstId, audit("AGENT_APP_VERSION_PUBLISHED", secondId));

        Map<String, Object> rolledBack = repository.rollbackVersion(
                applicationId, firstId, secondId, audit("AGENT_APP_ROLLED_BACK", firstId));
        assertEquals(firstId, rolledBack.get("id"));
        assertEquals(firstId, repository.findApplication(applicationId).orElseThrow().get("currentVersionId"));

        List<Map<String, Object>> records = repository.listPublishRecords(applicationId);
        assertEquals(3, records.size());
        assertEquals("ROLLBACK", records.getFirst().get("action"));
        assertEquals(secondId, records.getFirst().get("previousVersionId"));
        assertTrue(records.stream().anyMatch(record -> "PUBLISH".equals(record.get("action"))));

        assertEquals(5, jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_control_plane_audit"
                        + " WHERE action_code IN ('AGENT_APP_VERSION_CREATED','AGENT_APP_VERSION_PUBLISHED','AGENT_APP_ROLLED_BACK')",
                Integer.class));
    }

    @Test
    void concurrentPublishAllowsExactlyOneWinner() throws Exception {
        Map<String, Object> app = saveApplication("race-app");
        String applicationId = String.valueOf(app.get("id"));
        Map<String, Object> version = createVersion(applicationId);
        String versionId = String.valueOf(version.get("id"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CallableWithLatch[] callables = new CallableWithLatch[]{
                    new CallableWithLatch(repository, applicationId, versionId, start),
                    new CallableWithLatch(repository, applicationId, versionId, start)
            };
            Future<Object> first = pool.submit(callables[0]);
            Future<Object> second = pool.submit(callables[1]);
            start.countDown();
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            assertEquals(1, countSuccess(firstResult) + countSuccess(secondResult),
                    "并发发布必须恰好一个成功");
            assertEquals("PUBLISHED", repository.findVersion(versionId).orElseThrow().get("status"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateVersionNumberIsRejectedByUniqueConstraint() {
        Map<String, Object> app = saveApplication("dup-app");
        String applicationId = String.valueOf(app.get("id"));
        String insertVersion = "INSERT INTO agent_application_version"
                + "(id,application_id,version_no,status,model_code,prompt_id,prompt_version_id,config_json,created_by,created_at,updated_at)"
                + " VALUES(?,?,?,?,?,?,?,CAST(? AS JSON),?,?,?)";
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update(insertVersion, UUID.randomUUID().toString(), applicationId, 1, "DRAFT",
                "model-demo", "prompt-1", "pv-2", "{}", "admin", now, now);
        assertThrows(DuplicateKeyException.class, () -> jdbc.update(
                insertVersion, UUID.randomUUID().toString(), applicationId, 1, "DRAFT",
                "model-demo", "prompt-1", "pv-2", "{}", "admin", now, now),
                "(application_id, version_no) 唯一约束必须存在");
    }

    @Test
    void apiKeyIsStoredHashOnlyAndRevocationIsImmediate() {
        Map<String, Object> app = saveApplication("key-app");
        String applicationId = String.valueOf(app.get("id"));
        String plaintext = "ag_secret_plaintext_never_stored";
        String keyId = UUID.randomUUID().toString();
        Map<String, Object> key = Map.of(
                "id", keyId,
                "applicationId", applicationId,
                "keyHash", sha256(plaintext),
                "keyPrefix", plaintext.substring(0, 12),
                "status", "ACTIVE",
                "scopes", List.of("chat:completions"),
                "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString());
        repository.saveApiKey(key, audit("AGENT_API_KEY_CREATED", keyId));

        Map<String, Object> stored = repository.findApiKey(applicationId, keyId).orElseThrow();
        assertFalse(stored.containsKey("keyHash"), "仓库对外映射不得暴露 keyHash");
        assertEquals("ag_secret_pl", stored.get("keyPrefix"));
        String persistedHash = jdbc.queryForObject(
                "SELECT key_hash FROM agent_api_key WHERE id=?", String.class, keyId);
        assertEquals(sha256(plaintext), persistedHash, "数据库只能保存 SHA-256 hash");
        assertFalse(persistedHash.contains(plaintext));

        assertEquals(keyId, repository.findApiKeyByHash(sha256(plaintext)).orElseThrow().get("id"));
        Map<String, Object> revoked = repository.revokeApiKey(
                applicationId, keyId, Instant.now(), audit("AGENT_API_KEY_REVOKED", keyId)).orElseThrow();
        assertEquals("REVOKED", revoked.get("status"));
        assertEquals("REVOKED", repository.findApiKeyByHash(sha256(plaintext)).orElseThrow().get("status"),
                "撤销必须立即对后续校验生效");
        assertTrue(repository.revokeApiKey(applicationId, keyId, Instant.now(),
                audit("AGENT_API_KEY_REVOKED", keyId)).isEmpty(), "重复撤销不得成功");
    }

    @Test
    void archiveIsBlockedByActiveKeyAndAllowedAfterRevocation() {
        Map<String, Object> app = saveApplication("archive-app");
        String applicationId = String.valueOf(app.get("id"));
        String keyId = UUID.randomUUID().toString();
        repository.saveApiKey(Map.of(
                "id", keyId,
                "applicationId", applicationId,
                "keyHash", sha256("ag_active"),
                "keyPrefix", "ag_active",
                "status", "ACTIVE",
                "scopes", List.of("chat:completions"),
                "createdAt", Instant.now().toString(),
                "updatedAt", Instant.now().toString()), audit("AGENT_API_KEY_CREATED", keyId));

        assertFalse(repository.archiveApplication(applicationId, Instant.now(),
                audit("AGENT_APP_ARCHIVED", applicationId)), "存在有效 API Key 时不得归档");
        repository.revokeApiKey(applicationId, keyId, Instant.now(), audit("AGENT_API_KEY_REVOKED", keyId));
        assertTrue(repository.archiveApplication(applicationId, Instant.now(),
                audit("AGENT_APP_ARCHIVED", applicationId)));
        assertEquals("ARCHIVED", repository.findApplication(applicationId).orElseThrow().get("status"));
        assertFalse(repository.archiveApplication(applicationId, Instant.now(),
                audit("AGENT_APP_ARCHIVED", applicationId)), "重复归档必须返回 false");
        assertThrows(IllegalStateException.class,
                () -> repository.publishVersion(applicationId,
                String.valueOf(createVersion(applicationId).get("id")),
                        null, audit("AGENT_APP_VERSION_PUBLISHED", "v")),
                "已归档应用不能创建或发布版本");
    }

    private Map<String, Object> saveApplication(String code) {
        Instant now = Instant.now();
        Map<String, Object> app = new LinkedHashMap<>();
        app.put("id", UUID.randomUUID().toString());
        app.put("code", code);
        app.put("displayName", "测试应用");
        app.put("status", "DRAFT");
        app.put("createdAt", now.toString());
        app.put("updatedAt", now.toString());
        repository.saveApplication(app, audit("AGENT_APP_SAVED", app.get("id")));
        return repository.findApplication(String.valueOf(app.get("id"))).orElseThrow();
    }

    private Map<String, Object> createVersion(String applicationId) {
        Instant now = Instant.now();
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("id", UUID.randomUUID().toString());
        version.put("applicationId", applicationId);
        version.put("status", "DRAFT");
        version.put("modelCode", "model-demo");
        version.put("promptId", "prompt-1");
        version.put("promptVersionId", "pv-2");
        version.put("knowledgeBaseId", "kb-1");
        version.put("config", Map.of("temperature", 0.2));
        version.put("createdBy", "admin");
        version.put("createdAt", now.toString());
        version.put("updatedAt", now.toString());
        List<Map<String, Object>> bindings = List.of(Map.of(
                "id", UUID.randomUUID().toString(),
                "resourceType", "MCP_TOOL_VERSION",
                "resourceId", "tool-v1",
                "resourceVersion", "tv-3",
                "config", Map.of("confirm", true),
                "createdAt", now.toString()));
        return repository.createVersion(version, bindings, audit("AGENT_APP_VERSION_CREATED", version.get("id")));
    }

    private ControlPlaneAudit audit(String action, Object resourceId) {
        return new ControlPlaneAudit(UUID.randomUUID().toString(), "admin", action,
                "AGENT_APPLICATION_VERSION", String.valueOf(resourceId), Map.of(), Instant.now());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static int countSuccess(Object result) {
        return result instanceof Map<?, ?> map && "PUBLISHED".equals(map.get("status")) ? 1 : 0;
    }

    private record CallableWithLatch(
            JdbcAgentApplicationRepository repository,
            String applicationId,
            String versionId,
            CountDownLatch start
    )
            implements java.util.concurrent.Callable<Object> {
        @Override
        public Object call() throws Exception {
            start.await();
            try {
                return repository.publishVersion(applicationId, versionId, null,
                        new ControlPlaneAudit(UUID.randomUUID().toString(), "admin", "AGENT_APP_VERSION_PUBLISHED",
                                "AGENT_APPLICATION_VERSION", versionId, Map.of(), Instant.now()));
            } catch (Exception exception) {
                return exception;
            }
        }
    }
}
