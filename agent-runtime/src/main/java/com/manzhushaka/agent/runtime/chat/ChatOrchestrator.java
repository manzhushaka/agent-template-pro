package com.manzhushaka.agent.runtime.chat;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.agent.DomainAgentRegistry;
import com.manzhushaka.agent.runtime.agent.OwnedAction;
import com.manzhushaka.agent.runtime.agent.RegisteredDomainAgent;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.intent.IntentDecision;
import com.manzhushaka.agent.runtime.intent.IntentResolver;
import com.manzhushaka.agent.runtime.routing.ConversationRoutingContext;
import com.manzhushaka.agent.runtime.routing.CoordinatorRouter;
import com.manzhushaka.agent.runtime.routing.RouteDecision;
import com.manzhushaka.agent.runtime.routing.RouteSource;
import com.manzhushaka.agent.runtime.routing.RouteType;
import com.manzhushaka.agent.runtime.store.MessageAppend;
import com.manzhushaka.agent.runtime.store.RouteDecisionRecord;
import com.manzhushaka.agent.runtime.store.RouteMetrics;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.TimelineItem;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.spi.action.ActionMode;
import com.manzhushaka.agent.spi.action.AgentAction;
import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;
import com.manzhushaka.agent.spi.result.ActionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ChatOrchestrator {
    public static final String COORDINATOR_CODE = "group-assistant";
    public static final String COORDINATOR_NAME = "集团总智能体";
    private static final int EVENT_PAGE_LIMIT = 200;
    private static final int MODEL_HISTORY_LIMIT = 12;

    private final RuntimeStore store;
    private final DomainAgentRegistry registry;
    private final IntentResolver intentResolver;
    private final CoordinatorRouter coordinatorRouter;
    private final CoordinatorConversationResponder coordinatorConversationResponder;
    private final String routingMode;

    public ChatOrchestrator(
            RuntimeStore store,
            DomainAgentRegistry registry,
            List<IntentResolver> intentResolvers,
            List<CoordinatorRouter> coordinatorRouters,
            List<CoordinatorConversationResponder> coordinatorConversationResponders,
            @Value("${agent.routing.mode:coordinator}") String routingMode
    ) {
        this.store = store;
        this.registry = registry;
        this.intentResolver = intentResolvers.getFirst();
        this.coordinatorRouter = coordinatorRouters.getFirst();
        this.coordinatorConversationResponder = coordinatorConversationResponders.getFirst();
        this.routingMode = routingMode;
    }

    public Conversation createConversation(String visitorId) {
        return store.createConversation(visitorId);
    }

    public List<Conversation> conversations(String visitorId) {
        return store.listConversations(visitorId);
    }

    public List<ChatMessage> messages(String visitorId, String conversationId) {
        return store.messages(visitorId, conversationId);
    }

    public List<TimelineItem> timeline(String visitorId, String conversationId, long afterSequence, int limit) {
        return store.timeline(visitorId, conversationId, Math.max(afterSequence, 0), Math.clamp(limit, 1, 200));
    }

    public List<StreamEvent> events(String visitorId, String conversationId, long afterSequence) {
        return store.events(visitorId, conversationId, Math.max(afterSequence, 0), EVENT_PAGE_LIMIT);
    }

    public List<AgentTask> tasks() {
        return store.listTasks();
    }

    public List<RegisteredDomainAgent> registeredAgents() {
        return registry.enabledAgents();
    }

    public Optional<DomainAgentDescriptor> agentDescriptor(String agentCode) {
        return registry.findAgent(agentCode).map(RegisteredDomainAgent::descriptor);
    }

    public String routingMode() {
        return routingMode;
    }

    public RouteMetrics routeMetrics(String agentCode) {
        return store.routeMetrics(agentCode);
    }

    public List<StreamEvent> message(
            String visitorId,
            String conversationId,
            String content,
            String requestId
    ) {
        Conversation conversation = requireConversation(visitorId, conversationId);
        store.appendMessage(new MessageAppend(conversationId, "USER", content, "message.final", null, null));

        if ("flat".equalsIgnoreCase(routingMode)) {
            return flatRoute(visitorId, conversation, content, requestId);
        }
        RouteDecision decision = coordinatorRouter.route(
                content,
                routingContext(visitorId, conversation),
                publicDescriptors()
        );
        return handleRoute(visitorId, conversation, content, requestId, decision);
    }

    public List<StreamEvent> selectAgent(
            String visitorId,
            String conversationId,
            String targetAgentCode,
            long expectedRoutingVersion,
            String requestId
    ) {
        Conversation conversation = requireConversation(visitorId, conversationId);
        RegisteredDomainAgent target = visibleAgent(targetAgentCode);
        assertSwitchAllowed(visitorId, conversationId, targetAgentCode);
        if (!store.updateConversationRoute(
                visitorId, conversationId, expectedRoutingVersion, targetAgentCode)) {
            throw new BusinessException(ErrorCode.AGENT_ROUTE_VERSION_CONFLICT, "服务路由已变化，请刷新后重试");
        }
        RouteDecision decision = new RouteDecision(
                RouteType.DOMAIN_AGENT,
                targetAgentCode,
                1,
                RouteSource.USER_SELECTED,
                "USER_SELECTED_AGENT",
                List.of()
        );
        saveRoute(visitorId, conversation, requestId, decision);
        return List.of(routeEvent(conversationId, requestId, decision, target));
    }

    public List<StreamEvent> submitInput(
            String visitorId,
            String pendingId,
            Map<String, Object> values,
            String requestId
    ) {
        PendingAction pending = store.findPending(visitorId, pendingId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待补充操作不存在、已过期或不属于当前访客")
        );
        pending.merge(values);
        store.savePending(pending);
        OwnedAction owned = ownedAction(pending.actionCode());
        assertOwnership(pending.domainCode(), owned);
        return collectOrRun(visitorId, pending.conversationId(), requestId, owned, pending.input(), pending);
    }

    public List<StreamEvent> confirm(
            String visitorId,
            String taskId,
            int confirmationVersion,
            String decision,
            String requestId
    ) {
        AgentTask task = store.findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        if ("CONFIRMED".equals(decision)) {
            return executeTask(task, confirmationVersion, requestId);
        }
        AgentTask cancelled = store.transitionTask(
                visitorId,
                taskId,
                TaskStatus.WAITING_CONFIRMATION,
                confirmationVersion,
                TaskStatus.CANCELLED,
                null
        ).orElseThrow(this::confirmationConflict);
        store.audit(visitorId, taskId, "TASK_CANCELLED");
        RegisteredDomainAgent agent = agent(cancelled.domainCode());
        return List.of(systemEvent(
                "task.status",
                cancelled.conversationId(),
                requestId,
                agent,
                cancelled.actionCode(),
                Map.of("taskId", taskId, "status", cancelled.status().name())
        ));
    }

    public AgentTask task(String visitorId, String id) {
        return store.findTask(visitorId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
    }

    private List<StreamEvent> flatRoute(
            String visitorId,
            Conversation conversation,
            String content,
            String requestId
    ) {
        IntentDecision intent = intentResolver.resolve(
                content,
                registry.enabledAgents().stream()
                        .flatMap(agent -> agent.actions().values().stream())
                        .map(AgentAction::descriptor)
                        .toList()
        );
        OwnedAction owned = ownedAction(intent.actionCode());
        RouteDecision route = new RouteDecision(
                owned.agentCode().equals(conversation.activeAgentCode())
                        ? RouteType.KEEP_CURRENT_AGENT
                        : RouteType.DOMAIN_AGENT,
                owned.agentCode(),
                1,
                RouteSource.FLAT_COMPATIBILITY,
                "FLAT_ACTION_OWNER",
                List.of()
        );
        return runRouted(visitorId, conversation, requestId, route, owned, intent.input());
    }

    private List<StreamEvent> handleRoute(
            String visitorId,
            Conversation conversation,
            String content,
            String requestId,
            RouteDecision decision
    ) {
        if (decision.type() == RouteType.GENERAL_ASSISTANCE) {
            saveRoute(visitorId, conversation, requestId, decision);
            return List.of(coordinatorConversationMessage(visitorId, conversation, requestId, content, decision));
        }
        if (decision.type() == RouteType.CLARIFICATION_REQUIRED) {
            saveRoute(visitorId, conversation, requestId, decision);
            return List.of(coordinatorConversationMessage(visitorId, conversation, requestId, content, decision));
        }
        if (decision.type() == RouteType.UNSUPPORTED || decision.targetAgentCode() == null) {
            saveRoute(visitorId, conversation, requestId, decision);
            return List.of(
                    routeEvent(conversation.id(), requestId, decision, null),
                    coordinatorMessage(conversation.id(), requestId, "当前专业服务暂时无法处理这项需求。")
            );
        }

        RegisteredDomainAgent target = visibleAgent(decision.targetAgentCode());
        IntentDecision intent = intentResolver.resolve(
                content,
                target.actions().values().stream().map(AgentAction::descriptor).toList()
        );
        OwnedAction owned = ownedAction(intent.actionCode());
        assertOwnership(target.descriptor().code(), owned);
        return runRouted(visitorId, conversation, requestId, decision, owned, intent.input());
    }

    private List<StreamEvent> runRouted(
            String visitorId,
            Conversation conversation,
            String requestId,
            RouteDecision route,
            OwnedAction owned,
            Map<String, Object> input
    ) {
        RegisteredDomainAgent target = agent(owned.agentCode());
        if (!owned.agentCode().equals(conversation.activeAgentCode())) {
            assertSwitchAllowed(visitorId, conversation.id(), owned.agentCode());
            if (!store.updateConversationRoute(
                    visitorId,
                    conversation.id(),
                    conversation.routingVersion(),
                    owned.agentCode())) {
                throw new BusinessException(ErrorCode.AGENT_ROUTE_VERSION_CONFLICT, "服务路由已变化，请刷新后重试");
            }
        }
        saveRoute(visitorId, conversation, requestId, route);
        List<StreamEvent> result = new ArrayList<>();
        result.add(routeEvent(conversation.id(), requestId, route, target));
        result.addAll(collectOrRun(visitorId, conversation.id(), requestId, owned, input, null));
        return List.copyOf(result);
    }

    private List<StreamEvent> collectOrRun(
            String visitorId,
            String conversationId,
            String requestId,
            OwnedAction owned,
            Map<String, Object> input,
            PendingAction existing
    ) {
        AgentAction action = owned.action();
        RegisteredDomainAgent agent = agent(owned.agentCode());
        List<String> missing = action.descriptor().requiredFields().stream()
                .filter(field -> !input.containsKey(field) || String.valueOf(input.get(field)).isBlank())
                .toList();
        if (!missing.isEmpty()) {
            PendingAction pending = existing == null
                    ? store.savePending(new PendingAction(
                            "pa_" + UUID.randomUUID(),
                            conversationId,
                            owned.agentCode(),
                            action.descriptor().code(),
                            input,
                            Instant.now().plus(30, ChronoUnit.MINUTES)
                    ))
                    : existing;
            return List.of(systemEvent(
                    "form.request",
                    conversationId,
                    requestId,
                    agent,
                    action.descriptor().code(),
                    Map.of(
                            "pendingActionId", pending.id(),
                            "fields", missing.stream()
                                    .map(field -> Map.of(
                                            "name", field,
                                            "label", label(field),
                                            "required", true
                                    ))
                                    .toList()
                    )
            ));
        }
        if (existing != null) {
            store.removePending(existing.id());
        }
        if (action.descriptor().mode() == ActionMode.QUERY
                || action.descriptor().mode() == ActionMode.DRAFT) {
            return executeDirect(visitorId, conversationId, requestId, owned, input);
        }

        AgentTask candidate = new AgentTask(
                "tsk_" + UUID.randomUUID(),
                visitorId,
                conversationId,
                owned.agentCode(),
                action.descriptor().code(),
                requestId,
                input
        );
        candidate.prepareConfirmation(1, confirmationHash(action.descriptor().code(), input));
        AgentTask task = store.saveTask(candidate);
        if (!task.visitorId().equals(visitorId)) {
            throw new BusinessException(ErrorCode.REQUEST_DUPLICATED, "请求标识已被占用，请重新发起操作");
        }
        if (task.status() != TaskStatus.WAITING_CONFIRMATION) {
            return List.of(systemEvent(
                    "task.status",
                    task.conversationId(),
                    requestId,
                    agent,
                    task.actionCode(),
                    Map.of(
                            "taskId", task.id(),
                            "status", task.status().name(),
                            "externalRef", value(task.externalRef())
                    )
            ));
        }
        store.audit(visitorId, task.id(), "WAITING_CONFIRMATION");
        return List.of(systemEvent(
                "action.confirm",
                conversationId,
                requestId,
                agent,
                action.descriptor().code(),
                Map.of(
                        "taskId", task.id(),
                        "confirmationVersion", task.confirmationVersion(),
                        "title", action.descriptor().confirmationTitle(),
                        "summary", task.input()
                )
        ));
    }

    private List<StreamEvent> executeDirect(
            String visitorId,
            String conversationId,
            String requestId,
            OwnedAction owned,
            Map<String, Object> input
    ) {
        AgentAction action = owned.action();
        RegisteredDomainAgent agent = agent(owned.agentCode());
        ActionResult result = action.execute(
                new ActionContext(visitorId, conversationId, requestId, requestId), input
        );
        return List.of(
                assistantMessageEvent(conversationId, requestId, agent, action.descriptor().code(), result.summary()),
                systemEvent("card.render", conversationId, requestId, agent, action.descriptor().code(), Map.of(
                        "cardType", result.cardType(),
                        "data", result.cardData()
                ))
        );
    }

    private List<StreamEvent> executeTask(AgentTask task, int confirmationVersion, String requestId) {
        AgentTask dispatched = store.transitionTask(
                task.visitorId(),
                task.id(),
                TaskStatus.WAITING_CONFIRMATION,
                confirmationVersion,
                TaskStatus.DISPATCHED,
                null
        ).orElseThrow(this::confirmationConflict);
        OwnedAction owned = ownedAction(dispatched.actionCode());
        assertOwnership(dispatched.domainCode(), owned);
        RegisteredDomainAgent agent = agent(dispatched.domainCode());
        ActionResult result;
        try {
            result = owned.action().execute(new ActionContext(
                    dispatched.visitorId(),
                    dispatched.conversationId(),
                    requestId,
                    dispatched.idempotencyKey()
            ), dispatched.input());
        } catch (RuntimeException exception) {
            store.transitionTask(
                    dispatched.visitorId(), dispatched.id(), TaskStatus.DISPATCHED,
                    confirmationVersion, TaskStatus.FAILED, null
            );
            store.audit(dispatched.visitorId(), dispatched.id(), "ACTION_FAILED");
            throw exception;
        }

        TaskStatus targetStatus = result.waitingExternalResult()
                ? TaskStatus.WAITING_EXTERNAL_RESULT
                : TaskStatus.SUCCEEDED;
        String externalRef = "demo_" + dispatched.id().substring(4, 12);
        AgentTask completed = store.transitionTask(
                dispatched.visitorId(), dispatched.id(), TaskStatus.DISPATCHED,
                confirmationVersion, targetStatus, externalRef
        ).orElseThrow(() -> new BusinessException(
                ErrorCode.TASK_CONFIRMATION_CONFLICT, "任务执行状态已变化，请刷新后重试"
        ));
        store.audit(completed.visitorId(), completed.id(), "ACTION_" + completed.status());
        return List.of(
                assistantMessageEvent(
                        completed.conversationId(), requestId, agent, completed.actionCode(), result.summary()
                ),
                systemEvent("card.render", completed.conversationId(), requestId, agent, completed.actionCode(), Map.of(
                        "cardType", result.cardType(),
                        "data", result.cardData()
                )),
                systemEvent("task.status", completed.conversationId(), requestId, agent, completed.actionCode(), Map.of(
                        "taskId", completed.id(),
                        "status", completed.status().name(),
                        "externalRef", value(completed.externalRef())
                ))
        );
    }

    private ConversationRoutingContext routingContext(String visitorId, Conversation conversation) {
        boolean writePending = store.findActivePending(visitorId, conversation.id()).isPresent()
                || !store.findActiveTasks(visitorId, conversation.id()).isEmpty();
        return new ConversationRoutingContext(
                conversation.id(),
                conversation.activeAgentCode(),
                conversation.routingVersion(),
                writePending
        );
    }

    private void assertSwitchAllowed(String visitorId, String conversationId, String targetAgentCode) {
        store.findActivePending(visitorId, conversationId)
                .filter(pending -> !pending.domainCode().equals(targetAgentCode))
                .ifPresent(pending -> {
                    throw new BusinessException(
                            ErrorCode.AGENT_SWITCH_BLOCKED_BY_PENDING_ACTION,
                            "当前有待补充的操作，请先完成或取消后再切换服务"
                    );
                });
        boolean taskBlocks = store.findActiveTasks(visitorId, conversationId).stream()
                .anyMatch(task -> task.status() == TaskStatus.WAITING_CONFIRMATION
                        && !task.domainCode().equals(targetAgentCode));
        if (taskBlocks) {
            throw new BusinessException(
                    ErrorCode.AGENT_SWITCH_BLOCKED_BY_PENDING_ACTION,
                    "当前有待确认的操作，请先处理后再切换服务"
            );
        }
    }

    private void saveRoute(
            String visitorId,
            Conversation conversation,
            String requestId,
            RouteDecision route
    ) {
        store.saveRouteDecision(new RouteDecisionRecord(
                "rte_" + UUID.randomUUID(),
                visitorId,
                conversation.id(),
                requestId,
                1,
                conversation.activeAgentCode() == null ? COORDINATOR_CODE : conversation.activeAgentCode(),
                route.targetAgentCode(),
                route.type(),
                route.source(),
                route.confidence(),
                route.reasonCode(),
                route.clarificationCandidates(),
                Instant.now()
        ));
    }

    private StreamEvent routeEvent(
            String conversationId,
            String requestId,
            RouteDecision route,
            RegisteredDomainAgent target
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("coordinatorCode", COORDINATOR_CODE);
        payload.put("routeType", route.type().name());
        payload.put("routeSource", route.source().name());
        payload.put("reasonCode", route.reasonCode());
        if (target != null) {
            payload.put("targetAgentCode", target.descriptor().code());
            payload.put("targetAgentName", target.descriptor().displayName());
        }
        if (!route.clarificationCandidates().isEmpty()) {
            payload.put("candidates", route.clarificationCandidates().stream()
                    .map(this::visibleAgent)
                    .map(agent -> Map.of(
                            "code", agent.descriptor().code(),
                            "displayName", agent.descriptor().displayName(),
                            "iconKey", agent.descriptor().iconKey()
                    ))
                    .toList());
        }
        return systemEvent("agent.route", conversationId, requestId, target, null, payload);
    }

    private StreamEvent coordinatorMessage(String conversationId, String requestId, String content) {
        return coordinatorMessage(conversationId, requestId,
                new CoordinatorConversationReply(content, CoordinatorConversationReply.PRESET_FALLBACK));
    }

    private StreamEvent coordinatorConversationMessage(
            String visitorId,
            Conversation conversation,
            String requestId,
            String content,
            RouteDecision route
    ) {
        List<ChatMessage> messages = store.messages(visitorId, conversation.id()).stream()
                .filter(message -> "message.final".equals(message.eventType()))
                .filter(message -> List.of("USER", "ASSISTANT").contains(message.role()))
                .toList();
        int fromIndex = Math.max(0, messages.size() - MODEL_HISTORY_LIMIT);
        CoordinatorConversationReply reply = coordinatorConversationResponder.respond(
                new CoordinatorConversationRequest(
                        content,
                        messages.subList(fromIndex, messages.size()),
                        publicDescriptors(),
                        route.clarificationCandidates()
                )
        );
        return coordinatorMessage(conversation.id(), requestId, reply);
    }

    private StreamEvent coordinatorMessage(
            String conversationId,
            String requestId,
            CoordinatorConversationReply reply
    ) {
        long sequence = store.appendMessage(new MessageAppend(
                conversationId, "ASSISTANT", reply.content(), "message.final", COORDINATOR_CODE, null
        ));
        StreamEvent event = new StreamEvent(
                "message.final",
                conversationId,
                requestId,
                sequence,
                Instant.now(),
                Map.of(
                        "content", reply.content(),
                        "generationSource", reply.generationSource(),
                        "agent", Map.of("code", COORDINATOR_CODE, "name", COORDINATOR_NAME)
                )
        );
        store.saveEvent(event);
        return event;
    }

    private StreamEvent assistantMessageEvent(
            String conversationId,
            String requestId,
            RegisteredDomainAgent agent,
            String actionCode,
            String content
    ) {
        long sequence = store.appendMessage(new MessageAppend(
                conversationId,
                "ASSISTANT",
                content,
                "message.final",
                agent.descriptor().code(),
                actionCode
        ));
        Map<String, Object> payload = payload(agent, actionCode, Map.of("content", content));
        StreamEvent event = new StreamEvent(
                "message.final", conversationId, requestId, sequence, Instant.now(), payload
        );
        store.saveEvent(event);
        return event;
    }

    private StreamEvent systemEvent(
            String type,
            String conversationId,
            String requestId,
            RegisteredDomainAgent agent,
            String actionCode,
            Map<String, Object> values
    ) {
        long sequence = store.appendMessage(new MessageAppend(
                conversationId,
                "SYSTEM",
                "",
                type,
                agent == null ? null : agent.descriptor().code(),
                actionCode
        ));
        StreamEvent event = new StreamEvent(
                type,
                conversationId,
                requestId,
                sequence,
                Instant.now(),
                payload(agent, actionCode, values)
        );
        store.saveEvent(event);
        return event;
    }

    private Map<String, Object> payload(
            RegisteredDomainAgent agent,
            String actionCode,
            Map<String, Object> values
    ) {
        Map<String, Object> payload = new LinkedHashMap<>(values);
        if (agent != null) {
            payload.put("agent", Map.of(
                    "code", agent.descriptor().code(),
                    "name", agent.descriptor().displayName()
            ));
        }
        if (actionCode != null) {
            payload.put("actionCode", actionCode);
        }
        return Map.copyOf(payload);
    }

    private List<DomainAgentDescriptor> publicDescriptors() {
        return registry.enabledAgents().stream()
                .map(RegisteredDomainAgent::descriptor)
                .filter(DomainAgentDescriptor::visibleToVisitor)
                .toList();
    }

    private RegisteredDomainAgent visibleAgent(String code) {
        RegisteredDomainAgent agent = agent(code);
        if (!agent.descriptor().visibleToVisitor()) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND, "目标专业服务不存在或不可见");
        }
        return agent;
    }

    private RegisteredDomainAgent agent(String code) {
        return registry.findAgent(code).orElseThrow(
                () -> new BusinessException(ErrorCode.AGENT_NOT_FOUND, "目标专业服务不存在")
        );
    }

    private OwnedAction ownedAction(String actionCode) {
        return registry.findAction(actionCode).orElseThrow(
                () -> new BusinessException(ErrorCode.VALIDATION_FAILED, "未注册的领域动作")
        );
    }

    private void assertOwnership(String agentCode, OwnedAction owned) {
        if (!owned.agentCode().equals(agentCode)) {
            throw new BusinessException(
                    ErrorCode.AGENT_ACTION_OWNERSHIP_INVALID,
                    "动作不属于当前专业服务"
            );
        }
    }

    private Conversation requireConversation(String visitorId, String id) {
        return store.findConversation(visitorId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "会话不存在或不属于当前访客")
        );
    }

    private BusinessException confirmationConflict() {
        return new BusinessException(ErrorCode.TASK_CONFIRMATION_CONFLICT, "当前操作状态已变化，请刷新后重新确认");
    }

    private String confirmationHash(String actionCode, Map<String, Object> input) {
        String canonical = actionCode + '|' + input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + '=' + String.valueOf(entry.getValue()))
                .reduce("", (left, right) -> left.isEmpty() ? right : left + '&' + right);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private String value(String nullableValue) {
        return nullableValue == null ? "" : nullableValue;
    }

    private String label(String field) {
        return Map.ofEntries(
                Map.entry("city", "城市"),
                Map.entry("date", "日期"),
                Map.entry("guestName", "入住人"),
                Map.entry("bookingNo", "预订编号"),
                Map.entry("venue", "场馆"),
                Map.entry("ticketNo", "票务编号"),
                Map.entry("attraction", "景区"),
                Map.entry("keyword", "商品关键词"),
                Map.entry("productName", "商品名称"),
                Map.entry("quantity", "数量"),
                Map.entry("pickupNo", "提货编号")
        ).getOrDefault(field, field);
    }
}
