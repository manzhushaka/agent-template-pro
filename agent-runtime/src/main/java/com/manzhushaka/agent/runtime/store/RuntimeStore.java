package com.manzhushaka.agent.runtime.store;

import com.manzhushaka.agent.runtime.chat.ChatMessage;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.chat.PendingAction;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;

import java.util.List;
import java.util.Optional;

/** Durable boundary for visitor-owned runtime facts. */
public interface RuntimeStore {
    Conversation createConversation(String visitorId);
    Optional<Conversation> findConversation(String visitorId, String conversationId);
    List<Conversation> listConversations(String visitorId);
    long appendMessage(MessageAppend message);
    default long appendMessage(String conversationId, String role, String content, String eventType) {
        return appendMessage(new MessageAppend(conversationId, role, content, eventType, null, null));
    }
    List<ChatMessage> messages(String visitorId, String conversationId);
    PendingAction savePending(PendingAction pendingAction);
    Optional<PendingAction> findPending(String visitorId, String pendingId);
    void removePending(String pendingId);
    AgentTask saveTask(AgentTask task);
    Optional<AgentTask> findTask(String visitorId, String taskId);
    List<AgentTask> listTasks();
    Optional<AgentTask> transitionTask(
            String visitorId,
            String taskId,
            TaskStatus expectedStatus,
            int expectedConfirmationVersion,
            TaskStatus targetStatus,
            String externalRef
    );
    void saveEvent(StreamEvent event);
    List<StreamEvent> events(String visitorId, String conversationId, long afterSequence, int limit);
    void saveRouteDecision(RouteDecisionRecord decision);
    Optional<RouteDecisionRecord> latestRoute(String visitorId, String conversationId);
    Optional<PendingAction> findActivePending(String visitorId, String conversationId);
    List<AgentTask> findActiveTasks(String visitorId, String conversationId);
    boolean updateConversationRoute(
            String visitorId,
            String conversationId,
            long expectedVersion,
            String targetAgentCode
    );
    List<TimelineItem> timeline(String visitorId, String conversationId, long afterSequence, int limit);
    RouteMetrics routeMetrics(String agentCode);
    void audit(String visitorId, String taskId, String type);
}
