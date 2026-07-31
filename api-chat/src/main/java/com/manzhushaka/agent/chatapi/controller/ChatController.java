package com.manzhushaka.agent.chatapi.controller;

import com.manzhushaka.agent.chatapi.dto.*;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.identity.VisitorIdentityService;
import com.manzhushaka.agent.runtime.identity.VisitorCookie;
import com.manzhushaka.agent.runtime.task.AgentTask;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import java.util.*;

@RestController
@RequestMapping("/api/chat/v1")
public class ChatController {
    private final ChatOrchestrator orchestrator; private final VisitorIdentityService identity;
    public ChatController(ChatOrchestrator orchestrator, VisitorIdentityService identity) { this.orchestrator=orchestrator; this.identity=identity; }
    @PostMapping("/conversations") public ConversationResponse create(ServerWebExchange exchange) { return ConversationResponse.from(orchestrator.createConversation(visitor(exchange))); }
    @GetMapping("/conversations") public List<ConversationResponse> conversations(ServerWebExchange exchange) { return orchestrator.conversations(visitor(exchange)).stream().map(ConversationResponse::from).toList(); }
    @GetMapping("/conversations/{id}/messages") public Object messages(@PathVariable String id, ServerWebExchange exchange) { return orchestrator.messages(visitor(exchange), id); }
    @PostMapping(value="/conversations/{id}/messages:stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> message(@PathVariable String id, @RequestHeader(value="X-Client-Request-Id", required=false) String requestId, @Valid @RequestBody ChatRequest request, ServerWebExchange exchange) { return events(orchestrator.message(visitor(exchange), id, request.content(), requestId(requestId))); }
    @PostMapping(value="/pending-actions/{id}/input", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> input(@PathVariable String id, @RequestHeader(value="X-Client-Request-Id", required=false) String requestId, @RequestBody PendingInputRequest request, ServerWebExchange exchange) { return events(orchestrator.submitInput(visitor(exchange), id, request.input() == null ? Map.of() : request.input(), requestId(requestId))); }
    @PostMapping(value="/tasks/{id}/confirm", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<StreamEvent>> confirm(@PathVariable String id, @RequestHeader(value="X-Client-Request-Id", required=false) String requestId, @Valid @RequestBody ConfirmRequest request, ServerWebExchange exchange) { return events(orchestrator.confirm(visitor(exchange), id, request.confirmationVersion(), request.decision(), requestId(requestId))); }
    @GetMapping("/tasks/{id}") public TaskResponse task(@PathVariable String id, ServerWebExchange exchange) { return TaskResponse.from(orchestrator.task(visitor(exchange), id)); }
    @PostMapping("/visitor/data-deletion") @ResponseStatus(HttpStatus.ACCEPTED) public Map<String,String> deletion() { return Map.of("status", "ACCEPTED", "message", "数据删除请求已登记"); }
    private Flux<ServerSentEvent<StreamEvent>> events(List<StreamEvent> events) { return Flux.fromIterable(events).map(event -> ServerSentEvent.builder(event).event(event.type()).id(String.valueOf(event.sequence())).build()); }
    private String visitor(ServerWebExchange exchange) { String cookie=Optional.ofNullable(exchange.getRequest().getCookies().getFirst("agent_visitor")).map(HttpCookie::getValue).orElse(null); String visitor=identity.resolve(cookie); VisitorCookie issued=identity.cookie(visitor); exchange.getResponse().addCookie(ResponseCookie.from(issued.name(), issued.value()).httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(issued.maxAgeSeconds()).build()); return visitor; }
    private String requestId(String id) { return id == null || id.isBlank() ? "req_" + UUID.randomUUID() : id; }
}
