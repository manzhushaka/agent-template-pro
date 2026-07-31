package com.manzhushaka.agent.runtime.chat;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.spi.action.*;
import com.manzhushaka.agent.spi.context.ActionContext;
import com.manzhushaka.agent.spi.domain.DomainModule;
import com.manzhushaka.agent.spi.result.ActionResult;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ChatOrchestrator {
    private final RuntimeStore store;
    private final Map<String, AgentAction> actions;
    public ChatOrchestrator(RuntimeStore store, List<DomainModule> modules) {
        this.store = store;
        this.actions = new LinkedHashMap<>();
        modules.forEach(module -> module.actions().forEach(action -> actions.put(action.descriptor().code(), action)));
    }
    public Conversation createConversation(String visitorId) { return store.createConversation(visitorId); }
    public List<Conversation> conversations(String visitorId) { return store.listConversations(visitorId); }
    public List<ChatMessage> messages(String visitorId, String conversationId) { return store.messages(visitorId, conversationId); }
    public List<AgentTask> tasks() { return store.listTasks(); }

    public List<StreamEvent> message(String visitorId, String conversationId, String content, String requestId) {
        requireConversation(visitorId, conversationId);
        store.appendMessage(conversationId, "USER", content, "message.final");
        AgentAction action = select(content);
        Map<String,Object> input = extract(content, action.descriptor());
        return collectOrRun(visitorId, conversationId, requestId, action, input);
    }
    public List<StreamEvent> submitInput(String visitorId, String pendingId, Map<String,Object> values, String requestId) {
        PendingAction pending = store.findPending(visitorId, pendingId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "待补充操作不存在或不属于当前访客"));
        pending.merge(values);
        AgentAction action = action(pending.actionCode());
        return collectOrRun(visitorId, pending.conversationId(), requestId, action, pending.input(), pending);
    }
    public List<StreamEvent> confirm(String visitorId, String taskId, int confirmationVersion, String decision, String requestId) {
        AgentTask task = store.findTask(visitorId, taskId).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客"));
        if (task.status() != TaskStatus.WAITING_CONFIRMATION || task.confirmationVersion() != confirmationVersion) throw new BusinessException(ErrorCode.TASK_CONFIRMATION_CONFLICT, "当前操作状态已变化，请刷新后重新确认");
        if (!"CONFIRMED".equals(decision)) { task.status(TaskStatus.CANCELLED); store.audit(visitorId, taskId, "TASK_CANCELLED"); return List.of(event("task.status", task.conversationId(), requestId, Map.of("taskId", taskId, "status", task.status().name()))); }
        return executeTask(task, requestId);
    }
    public AgentTask task(String visitorId, String id) { return store.findTask(visitorId, id).orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")); }

    private List<StreamEvent> collectOrRun(String visitorId, String conversationId, String requestId, AgentAction action, Map<String,Object> input) { return collectOrRun(visitorId, conversationId, requestId, action, input, null); }
    private List<StreamEvent> collectOrRun(String visitorId, String conversationId, String requestId, AgentAction action, Map<String,Object> input, PendingAction existing) {
        List<String> missing = action.descriptor().requiredFields().stream().filter(field -> !input.containsKey(field) || String.valueOf(input.get(field)).isBlank()).toList();
        if (!missing.isEmpty()) {
            PendingAction pending = existing == null ? store.savePending(new PendingAction("pa_" + UUID.randomUUID(), conversationId, action.descriptor().code(), input, Instant.now().plus(30, ChronoUnit.MINUTES))) : existing;
            return List.of(event("form.request", conversationId, requestId, Map.of("pendingActionId", pending.id(), "actionCode", action.descriptor().code(), "fields", missing.stream().map(field -> Map.of("name", field, "label", label(field), "required", true)).toList())));
        }
        if (existing != null) store.removePending(existing.id());
        if (action.descriptor().mode() == ActionMode.QUERY || action.descriptor().mode() == ActionMode.DRAFT) return executeDirect(visitorId, conversationId, requestId, action, input);
        AgentTask task = store.saveTask(new AgentTask("tsk_" + UUID.randomUUID(), visitorId, conversationId, action.descriptor().code(), requestId, input));
        task.status(TaskStatus.WAITING_CONFIRMATION); task.confirmationVersion(1); store.audit(visitorId, task.id(), "WAITING_CONFIRMATION");
        return List.of(event("action.confirm", conversationId, requestId, Map.of("taskId", task.id(), "confirmationVersion", 1, "title", action.descriptor().confirmationTitle(), "summary", input)));
    }
    private List<StreamEvent> executeDirect(String visitorId, String conversationId, String requestId, AgentAction action, Map<String,Object> input) {
        ActionResult result = action.execute(new ActionContext(visitorId, conversationId, requestId, requestId), input);
        long seq = store.appendMessage(conversationId, "ASSISTANT", result.summary(), "message.final");
        return List.of(new StreamEvent("message.final", conversationId, requestId, seq, Instant.now(), Map.of("content", result.summary())), event("card.render", conversationId, requestId, Map.of("cardType", result.cardType(), "data", result.cardData())));
    }
    private List<StreamEvent> executeTask(AgentTask task, String requestId) {
        task.status(TaskStatus.DISPATCHED);
        AgentAction action = action(task.actionCode());
        ActionResult result = action.execute(new ActionContext(task.visitorId(), task.conversationId(), requestId, task.idempotencyKey()), task.input());
        task.status(result.waitingExternalResult() ? TaskStatus.WAITING_EXTERNAL_RESULT : TaskStatus.SUCCEEDED);
        task.externalRef("demo_" + task.id().substring(4, 12)); store.audit(task.visitorId(), task.id(), "ACTION_" + task.status());
        long seq = store.appendMessage(task.conversationId(), "ASSISTANT", result.summary(), "message.final");
        return List.of(new StreamEvent("message.final", task.conversationId(), requestId, seq, Instant.now(), Map.of("content", result.summary())), event("card.render", task.conversationId(), requestId, Map.of("cardType", result.cardType(), "data", result.cardData())), event("task.status", task.conversationId(), requestId, Map.of("taskId", task.id(), "status", task.status().name(), "externalRef", task.externalRef())));
    }
    private AgentAction select(String content) { String c = content.toLowerCase(Locale.ROOT); if (c.contains("预约") || c.contains("提交")) return action("demo.appointment.create"); if (c.contains("配送") || c.contains("进度") || c.contains("物流")) return action("demo.delivery.track"); return action("demo.weather.query"); }
    private AgentAction action(String code) { AgentAction action = actions.get(code); if (action == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未注册的领域动作"); return action; }
    private Map<String,Object> extract(String content, ActionDescriptor descriptor) { Map<String,Object> input = new LinkedHashMap<>(); for (String city : List.of("北京", "上海", "广州", "深圳", "杭州", "成都", "武汉")) if (content.contains(city)) input.put("city", city); if (content.contains("明天")) input.put("date", "明天"); if (content.contains("今天")) input.put("date", "今天"); if (content.contains("张三")) input.put("name", "张三"); return input; }
    private Conversation requireConversation(String visitorId, String id) { return store.findConversation(visitorId, id).orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "会话不存在或不属于当前访客")); }
    private StreamEvent event(String type, String conversationId, String requestId, Map<String,Object> payload) { long seq = store.appendMessage(conversationId, "SYSTEM", "", type); return new StreamEvent(type, conversationId, requestId, seq, Instant.now(), payload); }
    private String label(String field) { return Map.of("city", "城市", "date", "日期", "name", "姓名", "trackingNo", "运单号").getOrDefault(field, field); }
}
