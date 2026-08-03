package com.manzhushaka.agent.infrastructure.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationReply;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationRequest;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.chat.CoordinatorPromptBuilder;
import com.manzhushaka.agent.runtime.chat.PresetCoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Profile("model-minimax")
@Order(10)
public class MiniMaxCoordinatorConversationResponder implements CoordinatorConversationResponder {
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final URI messagesUri;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final PresetCoordinatorConversationResponder fallback;
    private final HttpClient httpClient;
    private final TraceRecorder traceRecorder;

    public MiniMaxCoordinatorConversationResponder(
            @Value("${agent.model.minimax.endpoint:https://api.minimaxi.com/anthropic}") String endpoint,
            @Value("${agent.model.minimax.api-key:}") String apiKey,
            @Value("${agent.model.minimax.model:minimax-m.2.7-highspeed}") String model,
            ObjectMapper objectMapper,
            PresetCoordinatorConversationResponder fallback,
            TraceRecorder traceRecorder
    ) {
        this.messagesUri = URI.create(messagesEndpoint(endpoint));
        this.apiKey = apiKey;
        this.model = model;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.traceRecorder = traceRecorder;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public CoordinatorConversationReply respond(CoordinatorConversationRequest request) {
        if (apiKey.isBlank()) {
            return fallback.respond(request);
        }
        Instant startedAt = Instant.now();
        try {
            String content = callModel(request);
            if (content.isBlank()) {
                recordModel(request, startedAt, SpanStatus.ERROR, "MODEL_EMPTY_RESPONSE");
                return fallback.respond(request);
            }
            recordModel(request, startedAt, SpanStatus.OK, null);
            return new CoordinatorConversationReply(content.trim(), CoordinatorConversationReply.MODEL);
        } catch (Exception exception) {
            recordModel(request, startedAt, SpanStatus.ERROR, "MODEL_CALL_FAILED");
            return fallback.respond(request);
        }
    }

    private void recordModel(
            CoordinatorConversationRequest request,
            Instant startedAt,
            SpanStatus status,
            String errorCode
    ) {
        traceRecorder.recordModel(
                request.traceId() == null ? request.requestId() : request.traceId(),
                request.visitorId(), request.conversationId(), request.requestId(),
                "minimax", model, 0, 0, status, errorCode, startedAt, Instant.now()
        );
    }

    private String callModel(CoordinatorConversationRequest request) throws IOException, InterruptedException {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1_024,
                "temperature", 0.4,
                "system", CoordinatorPromptBuilder.systemInstruction(request, model),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", CoordinatorPromptBuilder.conversationInput(request)
                ))
        );
        HttpRequest httpRequest = HttpRequest.newBuilder(messagesUri)
                .timeout(Duration.ofSeconds(45))
                .header("content-type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MiniMax request failed with HTTP " + response.statusCode());
        }
        JsonNode blocks = objectMapper.readTree(response.body()).path("content");
        for (JsonNode block : blocks) {
            if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                return block.get("text").asText();
            }
        }
        throw new IOException("MiniMax response did not contain text content");
    }

    private String messagesEndpoint(String endpoint) {
        String normalized = endpoint.endsWith("/")
                ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        return normalized.endsWith("/v1/messages") ? normalized : normalized + "/v1/messages";
    }
}
