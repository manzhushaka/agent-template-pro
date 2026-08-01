package com.manzhushaka.agent.chatapi.controller;

import com.manzhushaka.agent.chatapi.dto.AgentSelectRequest;
import com.manzhushaka.agent.chatapi.dto.BootstrapResponse;
import com.manzhushaka.agent.chatapi.dto.ChatMessageResponse;
import com.manzhushaka.agent.chatapi.dto.ChatRequest;
import com.manzhushaka.agent.chatapi.dto.ConfirmRequest;
import com.manzhushaka.agent.chatapi.dto.ConversationResponse;
import com.manzhushaka.agent.chatapi.dto.PendingInputRequest;
import com.manzhushaka.agent.chatapi.dto.PublicAgentResponse;
import com.manzhushaka.agent.chatapi.dto.TaskResponse;
import com.manzhushaka.agent.chatapi.dto.TimelineItemResponse;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.identity.VisitorCookie;
import com.manzhushaka.agent.runtime.identity.VisitorIdentityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat/v1")
public class ChatController {
    private final ChatOrchestrator orchestrator;
    private final VisitorIdentityService identity;

    public ChatController(ChatOrchestrator orchestrator, VisitorIdentityService identity) {
        this.orchestrator = orchestrator;
        this.identity = identity;
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap(ServerWebExchange exchange) {
        visitor(exchange);
        return new BootstrapResponse(
                new BootstrapResponse.ApplicationIdentity("group-consumer-service", "集团智慧服务"),
                new BootstrapResponse.CoordinatorIdentity(
                        ChatOrchestrator.COORDINATOR_CODE,
                        ChatOrchestrator.COORDINATOR_NAME,
                        "统一理解需求并协调专业服务"
                ),
                orchestrator.registeredAgents().stream()
                        .filter(agent -> agent.descriptor().visibleToVisitor())
                        .map(PublicAgentResponse::from)
                        .toList()
        );
    }

    @PostMapping("/conversations")
    public ConversationResponse create(ServerWebExchange exchange) {
        return conversation(orchestrator.createConversation(visitor(exchange)));
    }

    @GetMapping("/conversations")
    public List<ConversationResponse> conversations(ServerWebExchange exchange) {
        return orchestrator.conversations(visitor(exchange)).stream().map(this::conversation).toList();
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ChatMessageResponse> messages(@PathVariable String id, ServerWebExchange exchange) {
        return orchestrator.messages(visitor(exchange), id).stream()
                .map(message -> ChatMessageResponse.from(message, agentName(message.agentCode())))
                .toList();
    }

    @GetMapping("/conversations/{id}/timeline")
    public List<TimelineItemResponse> timeline(
            @PathVariable String id,
            @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
            @RequestParam(value = "limit", defaultValue = "200") int limit,
            ServerWebExchange exchange
    ) {
        return orchestrator.timeline(visitor(exchange), id, afterSequence, limit).stream()
                .map(item -> TimelineItemResponse.from(item, agentName(item.agentCode())))
                .toList();
    }

    @GetMapping("/conversations/{id}/events")
    public List<StreamEvent> events(
            @PathVariable String id,
            @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence,
            ServerWebExchange exchange
    ) {
        return orchestrator.events(visitor(exchange), id, afterSequence);
    }

    @PostMapping(value = "/conversations/{id}/messages:stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> message(
            @PathVariable String id,
            @RequestHeader(value = "X-Client-Request-Id", required = false) String requestId,
            @Valid @RequestBody ChatRequest request,
            ServerWebExchange exchange
    ) {
        return events(orchestrator.message(
                visitor(exchange), id, request.content(), requestId(requestId)
        ));
    }

    @PostMapping(value = "/conversations/{id}/agent:select", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> selectAgent(
            @PathVariable String id,
            @RequestHeader(value = "X-Client-Request-Id", required = false) String requestId,
            @Valid @RequestBody AgentSelectRequest request,
            ServerWebExchange exchange
    ) {
        return events(orchestrator.selectAgent(
                visitor(exchange), id, request.targetAgentCode(), request.expectedRoutingVersion(), requestId(requestId)
        ));
    }

    @PostMapping(value = "/pending-actions/{id}/input", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> input(
            @PathVariable String id,
            @RequestHeader(value = "X-Client-Request-Id", required = false) String requestId,
            @RequestBody PendingInputRequest request,
            ServerWebExchange exchange
    ) {
        return events(orchestrator.submitInput(
                visitor(exchange), id, request.input() == null ? Map.of() : request.input(), requestId(requestId)
        ));
    }

    @PostMapping(value = "/tasks/{id}/confirm", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> confirm(
            @PathVariable String id,
            @RequestHeader(value = "X-Client-Request-Id", required = false) String requestId,
            @Valid @RequestBody ConfirmRequest request,
            ServerWebExchange exchange
    ) {
        return events(orchestrator.confirm(
                visitor(exchange), id, request.confirmationVersion(), request.decision(), requestId(requestId)
        ));
    }

    @GetMapping("/tasks/{id}")
    public TaskResponse task(@PathVariable String id, ServerWebExchange exchange) {
        var task = orchestrator.task(visitor(exchange), id);
        return TaskResponse.from(task, agentName(task.domainCode()));
    }

    @PostMapping("/visitor/data-deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> deletion() {
        return Map.of("status", "ACCEPTED", "message", "数据删除请求已登记");
    }

    private ConversationResponse conversation(Conversation value) {
        return ConversationResponse.from(value, agentName(value.activeAgentCode()));
    }

    private String agentName(String code) {
        if (code == null || code.isBlank() || ChatOrchestrator.COORDINATOR_CODE.equals(code)) {
            return ChatOrchestrator.COORDINATOR_NAME;
        }
        return orchestrator.agentDescriptor(code)
                .map(descriptor -> descriptor.displayName())
                .orElse(ChatOrchestrator.COORDINATOR_NAME);
    }

    private Flux<ServerSentEvent<StreamEvent>> events(List<StreamEvent> events) {
        return Flux.fromIterable(events).map(event -> ServerSentEvent.builder(event)
                .event(event.type())
                .id(String.valueOf(event.sequence()))
                .build());
    }

    private String visitor(ServerWebExchange exchange) {
        String cookie = Optional.ofNullable(exchange.getRequest().getCookies().getFirst("agent_visitor"))
                .map(HttpCookie::getValue)
                .orElse(null);
        String visitor = identity.resolve(cookie);
        VisitorCookie issued = identity.cookie(visitor);
        exchange.getResponse().addCookie(ResponseCookie.from(issued.name(), issued.value())
                .httpOnly(true)
                .secure(isSecureRequest(exchange))
                .sameSite("Lax")
                .path("/")
                .maxAge(issued.maxAgeSeconds())
                .build());
        return visitor;
    }

    private boolean isSecureRequest(ServerWebExchange exchange) {
        if (exchange.getRequest().getSslInfo() != null) {
            return true;
        }
        String forwardedProto = exchange.getRequest().getHeaders().getFirst("X-Forwarded-Proto");
        return forwardedProto != null
                && "https".equalsIgnoreCase(forwardedProto.split(",", 2)[0].trim());
    }

    private String requestId(String id) {
        return id == null || id.isBlank() ? "req_" + UUID.randomUUID() : id;
    }
}
