package com.manzhushaka.agent.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the JDBC-only M4 fencing and compensation contract against a fresh MySQL 8.4 schema.
 * The migrations are copied from agent-boot test resources so this suite cannot accidentally pass
 * against an incomplete local database.
 */
class JdbcKnowledgeRepositoryIntegrationTest {
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("knowledge_it")
            .withUsername("knowledge")
            .withPassword("knowledge");

    private static final ObjectMapper JSON = new ObjectMapper();
    private JdbcTemplate jdbc;
    private JdbcKnowledgeRepository repository;
    private SpringAiJdbcVectorStore vectors;

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

        DataSource dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(dataSource);
        repository = new JdbcKnowledgeRepository(jdbc, JSON, transactions);
        vectors = new SpringAiJdbcVectorStore(jdbc, JSON, new DeterministicEmbeddingModel(), transactions);
    }

    @Test
    void expiredLeaseIsFencedWhenAnotherWorkerReclaimsTheJob() {
        Fixture fixture = fixture();
        Instant claimedAt = Instant.now();
        Map<String, Object> first = repository.claimIndexJobs("worker-a", claimedAt, claimedAt.plusSeconds(1), 1).getFirst();
        Map<String, Object> second = repository.claimIndexJobs("worker-b", claimedAt.plusSeconds(2), claimedAt.plusSeconds(30), 1).getFirst();

        assertFalse(String.valueOf(first.get("leaseOwner")).equals(second.get("leaseOwner")));
        assertFalse(String.valueOf(first.get("leaseToken")).equals(second.get("leaseToken")));
        assertFalse(repository.completeIndexJob(
                id(first), string(first, "leaseOwner"), string(first, "leaseToken"), List.of(), claimedAt.plusSeconds(2), audit()
        ));
        assertTrue(repository.completeIndexJob(
                id(second), string(second, "leaseOwner"), string(second, "leaseToken"), List.of(), claimedAt.plusSeconds(2), audit()
        ));
        assertEquals("SUCCEEDED", job(fixture.knowledgeBaseId()).get("status"));
    }

    @Test
    void vectorMutationAndMetadataRollBackTogetherWhenCallbackFails() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        Map<String, Object> claim = repository.claimIndexJobs("worker", now, now.plusSeconds(30), 1).getFirst();
        String chunkId = UUID.randomUUID().toString();
        Map<String, Object> chunk = chunk(fixture, chunkId, "atomic vector persistence");
        List<VectorStorePort.PreparedVectorDocument> prepared = vectors.prepare(List.of(new VectorStorePort.VectorDocument(
                chunkId, fixture.documentId(), fixture.documentVersionId(), "atomic vector persistence"
        )));

        assertThrows(IllegalStateException.class, () -> repository.completeIndexJobWithVectorMutation(
                id(claim), string(claim, "leaseOwner"), string(claim, "leaseToken"), List.of(chunk),
                () -> {
                    vectors.replacePrepared(fixture.knowledgeBaseId(), fixture.documentVersionId(), prepared);
                    throw new IllegalStateException("TEST_CALLBACK_FAILURE");
                },
                now.plusSeconds(1), audit()
        ));

        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_embedding WHERE document_version_id=?", fixture.documentVersionId()));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_chunk WHERE document_version_id=?", fixture.documentVersionId()));
        assertEquals("RUNNING", job(fixture.knowledgeBaseId()).get("status"));
    }

    @Test
    void deletionRacingCompletionLeavesNoChunkOrEmbeddingAndFencesTheOldWorker() throws Exception {
        Fixture fixture = fixture();
        KnowledgeBaseService service = new KnowledgeBaseService(repository, new InMemoryObjectStorage(), vectors);
        Instant now = Instant.now();
        Map<String, Object> claim = repository.claimIndexJobs("worker", now, now.plusSeconds(30), 1).getFirst();
        String chunkId = UUID.randomUUID().toString();
        Map<String, Object> chunk = chunk(fixture, chunkId, "deletion race content");
        List<VectorStorePort.PreparedVectorDocument> prepared = vectors.prepare(List.of(new VectorStorePort.VectorDocument(
                chunkId, fixture.documentId(), fixture.documentVersionId(), "deletion race content"
        )));
        ControlPlanePrincipal administrator = new ControlPlanePrincipal(
                "administrator", "ADMIN", Set.of(ControlPlaneService.KNOWLEDGE_READ, ControlPlaneService.KNOWLEDGE_WRITE)
        );
        CountDownLatch started = new CountDownLatch(1);

        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> completed = workers.submit(() -> {
                started.await();
                return repository.completeIndexJobWithVectorMutation(
                        id(claim), string(claim, "leaseOwner"), string(claim, "leaseToken"), List.of(chunk),
                        () -> vectors.replacePrepared(fixture.knowledgeBaseId(), fixture.documentVersionId(), prepared),
                        Instant.now(), audit()
                );
            });
            Future<?> deleted = workers.submit(() -> {
                started.await();
                service.deleteDocument(administrator, fixture.documentId());
                return null;
            });
            started.countDown();
            completed.get();
            deleted.get();
        }

        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_chunk WHERE document_id=?", fixture.documentId()));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_embedding WHERE document_id=?", fixture.documentId()));
        assertFalse(repository.completeIndexJobWithVectorMutation(
                id(claim), string(claim, "leaseOwner"), string(claim, "leaseToken"), List.of(chunk),
                () -> vectors.replacePrepared(fixture.knowledgeBaseId(), fixture.documentVersionId(), prepared),
                Instant.now(), audit()
        ));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_chunk WHERE document_id=?", fixture.documentId()));
        assertEquals(0, count("SELECT COUNT(*) FROM agent_knowledge_embedding WHERE document_id=?", fixture.documentId()));
    }

    @Test
    void disabledKnowledgeBaseCannotBeClaimedOrCompleted() {
        Fixture fixture = fixture();
        Instant now = Instant.now();
        Map<String, Object> claim = repository.claimIndexJobs("worker", now, now.plusSeconds(30), 1).getFirst();
        repository.saveKnowledgeBase(base(fixture.knowledgeBaseId(), "DISABLED", now.plusSeconds(1)), audit());

        assertFalse(repository.completeIndexJob(
                id(claim), string(claim, "leaseOwner"), string(claim, "leaseToken"), List.of(), now.plusSeconds(1), audit()
        ));
        assertTrue(repository.claimIndexJobs("another-worker", now.plusSeconds(2), now.plusSeconds(30), 1).isEmpty());
        assertEquals("CANCELLED", job(fixture.knowledgeBaseId()).get("status"));
    }

    @Test
    void preparedAndPendingCleanupSurviveRepositoryAndServiceRecreation() {
        Fixture fixture = fixture();
        InMemoryObjectStorage objects = new InMemoryObjectStorage();
        String pendingKey = "knowledge/" + fixture.knowledgeBaseId() + "/cleanup/pending";
        String preparedKey = "knowledge/" + fixture.knowledgeBaseId() + "/cleanup/prepared";
        objects.put(pendingKey, "text/plain", "pending orphan".getBytes(StandardCharsets.UTF_8));
        objects.put(preparedKey, "text/plain", "prepared orphan".getBytes(StandardCharsets.UTF_8));
        Instant expired = Instant.now().minusSeconds(301);
        String pendingId = UUID.randomUUID().toString();
        String preparedId = UUID.randomUUID().toString();
        repository.enqueueObjectCleanup(cleanup(pendingId, fixture.knowledgeBaseId(), pendingKey, "PENDING", expired), audit());
        repository.enqueueObjectCleanup(cleanup(preparedId, fixture.knowledgeBaseId(), preparedKey, "PREPARED", expired), audit());

        DataSource dataSource = jdbc.getDataSource();
        JdbcKnowledgeRepository restartedRepository = new JdbcKnowledgeRepository(
                new JdbcTemplate(dataSource), JSON, new DataSourceTransactionManager(dataSource)
        );
        KnowledgeBaseService restartedService = new KnowledgeBaseService(restartedRepository, objects, vectors);

        assertEquals(2, restartedService.compensateDeletedDocuments("restarted-worker", 10));
        assertThrows(IllegalStateException.class, () -> objects.get(pendingKey));
        assertThrows(IllegalStateException.class, () -> objects.get(preparedKey));
        assertEquals("SUCCEEDED", cleanup(pendingId).get("status"));
        assertEquals("SUCCEEDED", cleanup(preparedId).get("status"));
    }

    private Fixture fixture() {
        Instant now = Instant.now();
        String knowledgeBaseId = UUID.randomUUID().toString();
        String documentId = UUID.randomUUID().toString();
        String documentVersionId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        repository.saveKnowledgeBase(base(knowledgeBaseId, "ACTIVE", now), audit());
        repository.createDocumentVersionAndJob(
                Map.of(
                        "id", documentId,
                        "knowledgeBaseId", knowledgeBaseId,
                        "name", "fixture.txt",
                        "contentType", "text/plain",
                        "currentVersionId", documentVersionId,
                        "status", "QUEUED",
                        "createdAt", now.toString(),
                        "updatedAt", now.toString()
                ),
                Map.ofEntries(
                        Map.entry("id", documentVersionId),
                        Map.entry("documentId", documentId),
                        Map.entry("knowledgeBaseId", knowledgeBaseId),
                        Map.entry("version", 1),
                        Map.entry("objectKey", "knowledge/" + knowledgeBaseId + "/" + documentVersionId),
                        Map.entry("contentType", "text/plain"),
                        Map.entry("size", 1),
                        Map.entry("sha256", sha256("x")),
                        Map.entry("status", "QUEUED"),
                        Map.entry("createdAt", now.toString()),
                        Map.entry("updatedAt", now.toString())
                ),
                Map.of(
                        "id", jobId,
                        "knowledgeBaseId", knowledgeBaseId,
                        "documentId", documentId,
                        "documentVersionId", documentVersionId,
                        "nextAttemptAt", now.toString(),
                        "createdAt", now.toString(),
                        "updatedAt", now.toString()
                ),
                null,
                audit()
        );
        return new Fixture(knowledgeBaseId, documentId, documentVersionId, jobId);
    }

    private Map<String, Object> base(String id, String status, Instant now) {
        return Map.of(
                "id", id,
                "code", "kb-" + id.substring(0, 8),
                "displayName", "Integration knowledge base",
                "description", "JDBC integration fixture",
                "config", Map.of(),
                "status", status,
                "createdAt", now.toString(),
                "updatedAt", now.toString()
        );
    }

    private Map<String, Object> chunk(Fixture fixture, String chunkId, String content) {
        Instant now = Instant.now();
        return Map.of(
                "id", chunkId,
                "knowledgeBaseId", fixture.knowledgeBaseId(),
                "documentId", fixture.documentId(),
                "documentVersionId", fixture.documentVersionId(),
                "chunkIndex", 0,
                "content", content,
                "enabled", true,
                "createdAt", now.toString(),
                "updatedAt", now.toString()
        );
    }

    private Map<String, Object> cleanup(String id, String knowledgeBaseId, String objectKey, String status, Instant now) {
        return Map.ofEntries(
                Map.entry("id", id),
                Map.entry("objectKey", objectKey),
                Map.entry("knowledgeBaseId", knowledgeBaseId),
                Map.entry("documentVersionId", UUID.randomUUID().toString()),
                Map.entry("reasonCode", "OBJECT_UPLOAD_INTENT"),
                Map.entry("status", status),
                Map.entry("attempts", 0),
                Map.entry("createdAt", now.toString()),
                Map.entry("updatedAt", now.toString())
        );
    }

    private Map<String, Object> job(String knowledgeBaseId) {
        return repository.listIndexJobs(knowledgeBaseId).getFirst();
    }

    private Map<String, Object> cleanup(String id) {
        return jdbc.queryForMap("SELECT status FROM agent_knowledge_object_cleanup WHERE id=?", id);
    }

    private int count(String query, String parameter) {
        Integer value = jdbc.queryForObject(query, Integer.class, parameter);
        return value == null ? 0 : value;
    }

    private String id(Map<String, Object> value) {
        return string(value, "id");
    }

    private String string(Map<String, Object> value, String name) {
        return String.valueOf(value.get(name));
    }

    private ControlPlaneAudit audit() {
        return new ControlPlaneAudit(UUID.randomUUID().toString(), "integration-test", "TEST", "KNOWLEDGE", null, Map.of(), Instant.now());
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Fixture(String knowledgeBaseId, String documentId, String documentVersionId, String jobId) {
    }

    private static final class DeterministicEmbeddingModel implements EmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            throw new UnsupportedOperationException("The test store invokes embed(List<String>) directly.");
        }

        @Override
        public float[] embed(Document document) {
            return embed(document.getText());
        }

        @Override
        public float[] embed(String text) {
            int length = text == null ? 0 : text.length();
            return new float[] { Math.max(1, length), 1.0f, 0.5f };
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }
    }
}
