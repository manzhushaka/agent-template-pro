package com.manzhushaka.agent.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.FileSystemObjectStorage;
import com.manzhushaka.agent.controlplane.AgentApplicationRepository;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.ControlPlaneRepository;
import com.manzhushaka.agent.controlplane.InMemoryKnowledgeRepository;
import com.manzhushaka.agent.controlplane.InMemoryAgentApplicationRepository;
import com.manzhushaka.agent.controlplane.InMemoryObjectStorage;
import com.manzhushaka.agent.controlplane.InMemoryVectorStore;
import com.manzhushaka.agent.controlplane.JdbcAgentApplicationRepository;
import com.manzhushaka.agent.controlplane.JdbcKnowledgeRepository;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import com.manzhushaka.agent.controlplane.KnowledgeRepository;
import com.manzhushaka.agent.controlplane.ObjectStoragePort;
import com.manzhushaka.agent.controlplane.S3CompatibleObjectStorage;
import com.manzhushaka.agent.controlplane.SpringAiJdbcVectorStore;
import com.manzhushaka.agent.controlplane.VectorStorePort;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Path;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class KnowledgeBaseConfiguration {
    @Bean
    @Profile("runtime-jdbc")
    public KnowledgeRepository jdbcKnowledgeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        return new JdbcKnowledgeRepository(jdbcTemplate, objectMapper, transactionManager);
    }

    @Bean
    @Profile("runtime-jdbc")
    public AgentApplicationRepository jdbcAgentApplicationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcAgentApplicationRepository(jdbcTemplate, objectMapper, transactionManager);
    }

    @Bean
    @Profile("!runtime-jdbc")
    public KnowledgeRepository inMemoryKnowledgeRepository() {
        return new InMemoryKnowledgeRepository();
    }

    @Bean
    @Profile("!runtime-jdbc")
    public AgentApplicationRepository inMemoryAgentApplicationRepository() {
        return new InMemoryAgentApplicationRepository();
    }

    @Bean
    @Profile("runtime-jdbc & !knowledge-s3")
    public ObjectStoragePort fileSystemObjectStorage(@Value("${agent.knowledge.storage-directory:./data/knowledge}") String directory) {
        return new FileSystemObjectStorage(Path.of(directory));
    }

    @Bean
    @Profile("knowledge-s3")
    public ObjectStoragePort s3CompatibleObjectStorage(
            @Value("${agent.knowledge.s3.endpoint}") String endpoint,
            @Value("${agent.knowledge.s3.allowed-hosts}") String allowedHosts,
            @Value("${agent.knowledge.s3.bucket}") String bucket,
            @Value("${agent.knowledge.s3.region}") String region,
            @Value("${agent.knowledge.s3.access-key-id}") String accessKeyId,
            @Value("${agent.knowledge.s3.secret-access-key}") String secretAccessKey,
            @Value("${agent.knowledge.s3.timeout:PT10S}") Duration timeout
    ) {
        return new S3CompatibleObjectStorage(URI.create(endpoint), bucket, region, accessKeyId, secretAccessKey, timeout, hosts(allowedHosts));
    }

    @Bean
    @Profile("!runtime-jdbc & !knowledge-s3")
    public ObjectStoragePort inMemoryObjectStorage() {
        return new InMemoryObjectStorage();
    }

    @Bean
    @Profile("runtime-jdbc & knowledge-embedding-jdbc")
    public VectorStorePort springAiJdbcVectorStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            EmbeddingModel embeddingModel,
            PlatformTransactionManager transactionManager
    ) {
        return new SpringAiJdbcVectorStore(jdbcTemplate, objectMapper, embeddingModel, transactionManager);
    }

    @Bean
    @Profile("!knowledge-embedding-jdbc")
    public VectorStorePort developmentVectorStore() {
        return new InMemoryVectorStore();
    }

    @Bean
    public KnowledgeBaseService knowledgeBaseService(KnowledgeRepository repository, ObjectStoragePort objectStorage, VectorStorePort vectorStore) {
        return new KnowledgeBaseService(repository, objectStorage, vectorStore);
    }

    @Bean
    public AgentApplicationService agentApplicationService(
            AgentApplicationRepository applicationRepository,
            ControlPlaneRepository controlPlaneRepository,
            KnowledgeRepository knowledgeRepository,
            @Value("${agent.model.active-code:}") String activeModelCode
    ) {
        return new AgentApplicationService(
                applicationRepository,
                controlPlaneRepository,
                knowledgeRepository,
                activeModelCode);
    }

    private Set<String> hosts(String configured) {
        return Arrays.stream(configured == null ? new String[0] : configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
