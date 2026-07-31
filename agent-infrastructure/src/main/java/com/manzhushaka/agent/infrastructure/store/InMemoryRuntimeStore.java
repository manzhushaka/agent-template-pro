package com.manzhushaka.agent.infrastructure.store;

import com.manzhushaka.agent.runtime.chat.*;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Local/demo store. Production deployments replace this bean with a MySQL implementation. */
@Repository
@Primary
public class InMemoryRuntimeStore implements RuntimeStore {
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, List<ChatMessage>> messages = new ConcurrentHashMap<>();
    private final Map<String, PendingAction> pending = new ConcurrentHashMap<>();
    private final Map<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final List<Map<String,String>> audit = Collections.synchronizedList(new ArrayList<>());
    public Conversation createConversation(String visitorId) { String id = "cnv_" + UUID.randomUUID(); Instant now=Instant.now(); Conversation conversation=new Conversation(id, visitorId, "gth_" + UUID.randomUUID(), "新会话", now, now); conversations.put(id, conversation); messages.put(id, Collections.synchronizedList(new ArrayList<>())); sequences.put(id, new AtomicLong()); return conversation; }
    public Optional<Conversation> findConversation(String visitorId, String id) { return Optional.ofNullable(conversations.get(id)).filter(value -> value.visitorId().equals(visitorId)); }
    public List<Conversation> listConversations(String visitorId) { return conversations.values().stream().filter(value -> value.visitorId().equals(visitorId)).sorted(Comparator.comparing(Conversation::lastMessageAt).reversed()).toList(); }
    public long appendMessage(String conversationId, String role, String content, String eventType) { long sequence=sequences.get(conversationId).incrementAndGet(); messages.get(conversationId).add(new ChatMessage(sequence, role, content, eventType, Instant.now())); return sequence; }
    public List<ChatMessage> messages(String visitorId, String conversationId) { findConversation(visitorId, conversationId).orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_FORBIDDEN, "会话不存在或不属于当前访客")); return List.copyOf(messages.getOrDefault(conversationId, List.of())); }
    public PendingAction savePending(PendingAction value) { pending.put(value.id(), value); return value; }
    public Optional<PendingAction> findPending(String visitorId, String id) { return Optional.ofNullable(pending.get(id)).filter(value -> findConversation(visitorId, value.conversationId()).isPresent()); }
    public void removePending(String id) { pending.remove(id); }
    public AgentTask saveTask(AgentTask value) { tasks.put(value.id(), value); return value; }
    public Optional<AgentTask> findTask(String visitorId, String id) { return Optional.ofNullable(tasks.get(id)).filter(value -> value.visitorId().equals(visitorId)); }
    public List<AgentTask> listTasks() { return tasks.values().stream().sorted(Comparator.comparing(AgentTask::createdAt).reversed()).toList(); }
    public void audit(String visitorId, String taskId, String type) { audit.add(Map.of("visitorId", visitorId, "taskId", taskId == null ? "" : taskId, "type", type)); }
}
