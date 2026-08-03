package com.manzhushaka.agent.consoleapi.config;

import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.controlplane.ControlPlaneRepository;
import com.manzhushaka.agent.controlplane.InMemoryControlPlaneRepository;
import com.manzhushaka.agent.controlplane.JdbcControlPlaneRepository;
import com.manzhushaka.agent.controlplane.ProviderConnectionTester;
import com.manzhushaka.agent.controlplane.SecretRefResolver;
import com.manzhushaka.agent.controlplane.McpControlPlaneService;
import com.manzhushaka.agent.controlplane.McpTransportClient;
import com.manzhushaka.agent.controlplane.security.AdminSessionStore;
import com.manzhushaka.agent.controlplane.security.InMemoryAdminSessionStore;
import com.manzhushaka.agent.controlplane.security.JdbcAdminSessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class ConsoleControlPlaneConfiguration {
    @Bean
    @Profile("runtime-jdbc")
    public ControlPlaneRepository jdbcControlPlaneRepository(
            JdbcTemplate jdbcTemplate,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcControlPlaneRepository(jdbcTemplate, objectMapper, transactionManager);
    }

    @Bean
    @Profile("!runtime-jdbc")
    public ControlPlaneRepository inMemoryControlPlaneRepository() {
        return new InMemoryControlPlaneRepository();
    }

    @Bean
    public ControlPlaneService controlPlaneService(
            ControlPlaneRepository repository,
            SecretRefResolver secretRefResolver,
            ProviderConnectionTester providerConnectionTester,
            @Value("${agent.console.provider-allowed-hosts:}") String allowedHosts,
            @Value("${agent.console.provider-test-timeout:PT5S}") Duration testTimeout
    ) {
        return new ControlPlaneService(
                repository,
                secretRefResolver,
                providerConnectionTester,
                hosts(allowedHosts),
                testTimeout
        );
    }

    @Bean
    public McpControlPlaneService mcpControlPlaneService(
            ControlPlaneService controlPlaneService,
            ControlPlaneRepository repository,
            SecretRefResolver secretRefResolver,
            McpTransportClient mcpTransportClient,
            @Value("${agent.console.mcp-allowed-hosts:}") String allowedHosts,
            @Value("${agent.console.mcp-allowed-stdio-commands:}") String allowedStdioCommands,
            @Value("${agent.console.mcp-stdio-enabled:false}") boolean stdioEnabled,
            @Value("${agent.console.mcp-timeout:PT5S}") Duration timeout
    ) {
        return new McpControlPlaneService(controlPlaneService, repository, secretRefResolver, mcpTransportClient,
                hosts(allowedHosts), commands(allowedStdioCommands), stdioEnabled, timeout);
    }

    @Bean
    @Profile("runtime-jdbc")
    public AdminSessionStore jdbcAdminSessionStore(JdbcTemplate jdbcTemplate) {
        return new JdbcAdminSessionStore(jdbcTemplate);
    }

    @Bean
    @Profile("!runtime-jdbc")
    public AdminSessionStore inMemoryAdminSessionStore() {
        return new InMemoryAdminSessionStore();
    }

    @Bean
    public SecretRefResolver secretRefResolver() {
        return new EnvironmentSecretRefResolver();
    }

    @Bean
    public ProviderConnectionTester providerConnectionTester() {
        return new HttpsProviderConnectionTester();
    }

    @Bean
    public McpTransportClient mcpTransportClient(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ControlledMcpTransportClient(objectMapper);
    }

    private Set<String> hosts(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(host -> !host.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private Set<String> commands(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(command -> !command.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
