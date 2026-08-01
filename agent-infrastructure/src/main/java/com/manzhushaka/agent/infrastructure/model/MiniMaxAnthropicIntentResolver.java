package com.manzhushaka.agent.infrastructure.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.intent.DeterministicIntentResolver;
import com.manzhushaka.agent.runtime.intent.IntentDecision;
import com.manzhushaka.agent.runtime.intent.IntentResolver;
import com.manzhushaka.agent.spi.action.ActionDescriptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** MiniMax Anthropic-compatible adapter with deterministic fallback. */
@Service
@Profile("model-minimax")
@Order(10)
public class MiniMaxAnthropicIntentResolver implements IntentResolver {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final URI messagesUri;
    private final ObjectMapper objectMapper;
    private final DeterministicIntentResolver fallback;
    private final HttpClient httpClient;

    public MiniMaxAnthropicIntentResolver(
            @Value("${agent.model.minimax.endpoint:https://api.minimaxi.com/anthropic}") String endpoint,
            @Value("${agent.model.minimax.api-key:}") String apiKey,
            @Value("${agent.model.minimax.model:minimax-m.2.7-highspeed}") String model,
            ObjectMapper objectMapper,
            DeterministicIntentResolver fallback
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.messagesUri = URI.create(messagesEndpoint(endpoint));
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public IntentDecision resolve(String content, List<ActionDescriptor> descriptors) {
        if (apiKey.isBlank()) {
            return fallback.resolve(content, descriptors);
        }
        try {
            String response = callModel(prompt(content, descriptors));
            Map<String, Object> decision = objectMapper.readValue(response, MAP_TYPE);
            String actionCode = String.valueOf(decision.get("actionCode"));
            if (descriptors.stream().noneMatch(descriptor -> descriptor.code().equals(actionCode))) {
                return fallback.resolve(content, descriptors);
            }
            Object input = decision.get("input");
            return new IntentDecision(
                    actionCode,
                    input instanceof Map<?, ?> values
                            ? values.entrySet().stream().collect(Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()),
                                    Map.Entry::getValue,
                                    (left, right) -> right,
                                    java.util.LinkedHashMap::new
                            ))
                            : Map.of()
            );
        } catch (Exception exception) {
            return fallback.resolve(content, descriptors);
        }
    }

    private String callModel(String prompt) throws IOException, InterruptedException {
        Map<String, Object> request = Map.of(
                "model", model,
                "max_tokens", 1024,
                "temperature", 0,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );
        HttpRequest httpRequest = HttpRequest.newBuilder(messagesUri)
                .timeout(Duration.ofSeconds(45))
                .header("content-type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MiniMax request failed with HTTP " + response.statusCode());
        }
        JsonNode content = objectMapper.readTree(response.body()).path("content");
        for (JsonNode block : content) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                return stripCodeFence(block.get("text").asText());
            }
        }
        throw new IOException("MiniMax response did not contain text content");
    }

    private String prompt(String content, List<ActionDescriptor> descriptors) {
        String actions = descriptors.stream()
                .map(descriptor -> descriptor.code() + " required=" + descriptor.requiredFields())
                .collect(Collectors.joining("; "));
        return "Return JSON only, with actionCode and input. Select only one listed action. "
                + "Actions: " + actions + ". User message: " + content;
    }

    private String stripCodeFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || lastFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, lastFence).trim();
    }

    private String messagesEndpoint(String endpoint) {
        String normalized = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return normalized.endsWith("/v1/messages") ? normalized : normalized + "/v1/messages";
    }
}
