package com.manzhushaka.agent.agentapi.controller;

import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.chat.AgentAppRuntimeContext;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controlled Agent chat API. Every call authenticates an API key, resolves the immutable published
 * version, verifies the pinned model is the connected one, and then runs through ChatOrchestrator:
 * the same store, task state machine, confirmation gate and audit chain as the visitor chat. No
 * second execution path is created.
 */
@RestController
@RequestMapping("/api/agent/v1")
public class OpenAgentChatController {
    private static final Logger LOG = LoggerFactory.getLogger(OpenAgentChatController.class);
    private static final int MAX_MESSAGE_LENGTH = 4_000;
    private static final int DEFAULT_TOP_K = 5;
    private static final double DEFAULT_THRESHOLD = 0.2;

    private final AgentApplicationService applicationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ChatOrchestrator orchestrator;
    private final TraceRecorder traceRecorder;

    public OpenAgentChatController(
            AgentApplicationService applicationService,
            KnowledgeBaseService knowledgeBaseService,
            ChatOrchestrator orchestrator,
            TraceRecorder traceRecorder
    ) {
        this.applicationService = applicationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.orchestrator = orchestrator;
        this.traceRecorder = traceRecorder;
    }

    @PostMapping(value = "/apps/{appCode}/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> chatCompletions(
            @PathVariable String appCode,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestIdHeader,
            @Valid @RequestBody ChatCompletionsRequest request
    ) {
        String keyId = applicationService.authenticateApiKey(appCode, apiKey);
        AgentApplicationService.PublishedAppSnapshot snapshot = applicationService.resolvePublishedAppForCall(appCode);
        String connectedModel = applicationService.activeModelCode();
        if (connectedModel.isBlank() || !connectedModel.equals(snapshot.modelCode())) {
            throw new IllegalStateException("AGENT_MODEL_NOT_CONNECTED");
        }
        String content = lastUserMessage(request.messages());
        String requestId = requestIdHeader == null || requestIdHeader.isBlank()
                ? UUID.randomUUID().toString() : requestIdHeader.trim();
        String prompt = applicationService.resolvePromptContent(snapshot.promptVersionId(), request.variables());
        String knowledgeContext = knowledgeContext(snapshot, content, request, requestId);
        // Each API key is its own runtime visitor so keys of the same application cannot read or
        // confirm each other's conversations and tasks. The key itself is never part of the id.
        String visitorId = "api-app-" + snapshot.appCode() + "-" + keyId;
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? orchestrator.createConversation(visitorId).id() : request.conversationId().trim();
        AgentAppRuntimeContext appContext = new AgentAppRuntimeContext(
                snapshot.appCode(),
                snapshot.appDisplayName(),
                prompt,
                knowledgeContext,
                snapshot.modelCode());
        List<StreamEvent> events = orchestrator.message(visitorId, conversationId, content, requestId, appContext);
        CompletionResult result = completion(events, conversationId, requestId, snapshot.modelCode());
        if (Boolean.TRUE.equals(request.stream())) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .cacheControl(CacheControl.noStore())
                    .body(streamResponse(requestId, snapshot.modelCode(), result));
        }
        Map<String, Object> body = new LinkedHashMap<>(result.body());
        body.put("keyId", keyId);
        body.put("conversationId", conversationId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.copyOf(body));
    }

    private String knowledgeContext(
            AgentApplicationService.PublishedAppSnapshot snapshot,
            String content,
            ChatCompletionsRequest request,
            String requestId
    ) {
        String knowledgeBaseId = snapshot.knowledgeBaseId();
        if (knowledgeBaseId == null) {
            return "";
        }
        Instant startedAt = Instant.now();
        int topK = request.topK() == null ? DEFAULT_TOP_K : Math.clamp(request.topK(), 1, 10);
        double threshold = request.threshold() == null ? DEFAULT_THRESHOLD : request.threshold();
        try {
            List<Map<String, Object>> matches = knowledgeBaseService.retrieveForRuntime(
                    knowledgeBaseId, content, topK, threshold
            );
            traceRecorder.recordRetrieval(
                    traceRecorder.traceIdFor(requestId), null, null, requestId,
                    knowledgeBaseId, matches.size(), SpanStatus.OK, null, startedAt, Instant.now()
            );
            return matches.stream()
                    .map(this::citationText)
                    .filter(item -> !item.isBlank())
                    .limit(6)
                    .collect(Collectors.joining("\n"));
        } catch (RuntimeException exception) {
            traceRecorder.recordRetrieval(
                    traceRecorder.traceIdFor(requestId), null, null, requestId,
                    knowledgeBaseId, 0, SpanStatus.ERROR, "RETRIEVAL_FAILED", startedAt, Instant.now()
            );
            throw exception;
        }
    }

    private String citationText(Map<String, Object> match) {
        Object citation = match.get("citation");
        if (!(citation instanceof Map<?, ?> value)) {
            return "";
        }
        Object content = value.get("content");
        return content == null ? "" : String.valueOf(content).trim();
    }

    private CompletionResult completion(
            List<StreamEvent> events,
            String conversationId,
            String requestId,
            String modelCode
    ) {
        String content = null;
        Map<String, Object> pending = null;
        for (StreamEvent event : events) {
            if ("message.final".equals(event.type())) {
                Object payload = event.payload().get("content");
                if (payload != null) {
                    content = String.valueOf(payload);
                }
            } else if ("action.confirm".equals(event.type()) && content == null) {
                pending = Map.copyOf(event.payload());
            }
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        if (content == null && pending != null) {
            message.put("content", "该请求需要二次确认后才能继续，未执行任何写操作。");
        } else {
            message.put("content", content == null ? "" : content);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", requestId);
        body.put("object", "chat.completion");
        body.put("created", System.currentTimeMillis() / 1000);
        body.put("model", modelCode);
        body.put("conversationId", conversationId);
        body.put("choices", List.of(Map.of(
                "index", 0,
                "message", Map.copyOf(message),
                "finish_reason", pending != null ? "requires_confirmation" : "stop")));
        body.put("usage", Map.of("prompt_tokens", 0, "completion_tokens", 0, "total_tokens", 0));
        if (pending != null) {
            body.put("x-pending-confirmation", pending);
        }
        return new CompletionResult(Map.copyOf(body), message.get("content").toString());
    }

    private Flux<ServerSentEvent<String>> streamResponse(String requestId, String modelCode, CompletionResult result) {
        List<ServerSentEvent<String>> chunks = new ArrayList<>();
        chunks.add(chunk(requestId, modelCode, Map.of("role", "assistant"), null, false));
        chunks.add(chunk(requestId, modelCode, Map.of("content", result.content()), null, false));
        chunks.add(chunk(requestId, modelCode, Map.of(), "stop", true));
        chunks.add(ServerSentEvent.builder("[DONE]").build());
        return Flux.fromIterable(chunks);
    }

    private ServerSentEvent<String> chunk(String requestId, String modelCode, Map<String, Object> delta, String finishReason, boolean finalChunk) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("index", 0);
        choice.put("delta", Map.copyOf(delta));
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", requestId);
        payload.put("object", "chat.completion.chunk");
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("model", modelCode);
        payload.put("choices", List.of(Map.copyOf(choice)));
        String json = json(payload);
        return ServerSentEvent.builder(json).id(requestId).build();
    }

    private String lastUserMessage(List<ChatMessageInput> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不能为空。");
        }
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessageInput message = messages.get(index);
            if ("user".equalsIgnoreCase(message.role()) && message.content() != null && !message.content().isBlank()) {
                if (message.content().length() > MAX_MESSAGE_LENGTH) {
                    throw new IllegalArgumentException("用户消息超过长度限制。");
                }
                return message.content().trim();
            }
        }
        throw new IllegalArgumentException("messages 中缺少 user 消息。");
    }

    private String json(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("响应序列化失败。", exception);
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message != null && message.startsWith("AGENT_API_KEY_")) {
            return error(HttpStatus.UNAUTHORIZED, message);
        }
        return error(HttpStatus.BAD_REQUEST, errorCode(exception));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, errorCode(exception));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        if (exception.code() == ErrorCode.CONVERSATION_FORBIDDEN) {
            return error(HttpStatus.NOT_FOUND, "AGENT_CONVERSATION_NOT_FOUND");
        }
        return error(HttpStatus.CONFLICT, "AGENT_RUNTIME_CONFLICT");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> failed(Exception exception) {
        LOG.error("开放 Agent API 处理失败", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_OPEN_API_UNAVAILABLE");
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(Map.of("error", Map.of("code", code, "message", code)));
    }

    private String errorCode(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.matches("[A-Z][A-Z0-9_]{3,60}")) {
            return message;
        }
        return exception instanceof IllegalArgumentException ? "AGENT_REQUEST_INVALID" : "AGENT_STATE_CONFLICT";
    }

    private record CompletionResult(Map<String, Object> body, String content) {
    }

    public record ChatCompletionsRequest(
            @Valid List<ChatMessageInput> messages,
            @Size(max = 100) String conversationId,
            Boolean stream,
            Map<String, Object> variables,
            Integer topK,
            Double threshold
    ) {
    }

    public record ChatMessageInput(
            @NotBlank @Size(max = 20) String role,
            @NotBlank @Size(max = 4_000) String content
    ) {
    }
}
