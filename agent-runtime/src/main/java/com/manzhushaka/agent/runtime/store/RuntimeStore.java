package com.manzhushaka.agent.runtime.store;

import com.manzhushaka.agent.runtime.chat.*;
import com.manzhushaka.agent.runtime.task.AgentTask;
import java.util.List;
import java.util.Optional;

public interface RuntimeStore {
    Conversation createConversation(String visitorId);
    Optional<Conversation> findConversation(String visitorId, String conversationId);
    List<Conversation> listConversations(String visitorId);
    long appendMessage(String conversationId, String role, String content, String eventType);
    List<ChatMessage> messages(String visitorId, String conversationId);
    PendingAction savePending(PendingAction pendingAction);
    Optional<PendingAction> findPending(String visitorId, String pendingId);
    void removePending(String pendingId);
    AgentTask saveTask(AgentTask task);
    Optional<AgentTask> findTask(String visitorId, String taskId);
    List<AgentTask> listTasks();
    void audit(String visitorId, String taskId, String type);
}
