package com.manzhushaka.agent.boot;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.runtime.store.TimelineItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ChatOrchestratorTest {
    @Autowired
    private ChatOrchestrator orchestrator;

    @Test
    void keepsGeneralConversationWithCoordinator() {
        Conversation conversation = orchestrator.createConversation("visitor-general");

        List<StreamEvent> response = orchestrator.message(
                "visitor-general", conversation.id(), "你好，我是小王，你是谁？", "general-request"
        );

        assertEquals(List.of("message.final"), eventTypes(response));
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) response.getFirst().payload().get("agent");
        assertEquals("group-assistant", agent.get("code"));
        assertEquals("PRESET_FALLBACK", response.getFirst().payload().get("generationSource"));
        assertTrue(String.valueOf(response.getFirst().payload().get("content")).contains("集团总智能体"));
        assertTrue(orchestrator.conversations("visitor-general").getFirst().activeAgentCode() == null);
    }

    @Test
    void doesNotInterruptAConversationWithDomainChoices() {
        Conversation conversation = orchestrator.createConversation("visitor-chatty");

        List<StreamEvent> response = orchestrator.message(
                "visitor-chatty", conversation.id(), "我想跟你聊聊酒店和旅游，你陪我说说话吧", "chatty-request"
        );

        assertEquals(List.of("message.final"), eventTypes(response));
        assertEquals("当然可以，我就在这里陪你聊。你想聊点什么？", response.getFirst().payload().get("content"));
        assertEquals("PRESET_FALLBACK", response.getFirst().payload().get("generationSource"));
        assertTrue(orchestrator.conversations("visitor-chatty").getFirst().activeAgentCode() == null);
    }

    @Test
    void reportsPresetFallbackInsteadOfPretendingAModelAnswered() {
        Conversation conversation = orchestrator.createConversation("visitor-model-question");

        List<StreamEvent> response = orchestrator.message(
                "visitor-model-question", conversation.id(), "你用的是什么大模型", "model-question"
        );

        assertEquals("PRESET_FALLBACK", response.getFirst().payload().get("generationSource"));
        assertTrue(String.valueOf(response.getFirst().payload().get("content")).contains("没有使用可用的大模型"));
    }

    @Test
    void routesBeforeCollectingInputAndKeepsTheOwningDomain() {
        Conversation conversation = orchestrator.createConversation("visitor-a");
        List<StreamEvent> requested = orchestrator.message(
                "visitor-a", conversation.id(), "想查询酒店房态", "request-1"
        );

        assertEquals(List.of("agent.route", "form.request"), eventTypes(requested));
        assertEquals("hotel", requested.getFirst().payload().get("targetAgentCode"));
        StreamEvent form = requested.get(1);
        assertAgent(form, "hotel", "hotel.room.search");
        String pendingId = (String) form.payload().get("pendingActionId");

        List<StreamEvent> result = orchestrator.submitInput(
                "visitor-a", pendingId, Map.of("city", "上海", "date", "明天"), "request-2"
        );
        assertEquals(List.of("message.final", "card.render"), eventTypes(result));
        result.forEach(event -> assertAgent(event, "hotel", "hotel.room.search"));
        assertThrows(BusinessException.class, () -> orchestrator.messages("visitor-b", conversation.id()));
    }

    @Test
    void persistsRecoverableEventsAndConsumesConfirmationOnlyOnce() {
        Conversation conversation = orchestrator.createConversation("visitor-a");
        List<StreamEvent> confirmation = orchestrator.message(
                "visitor-a", conversation.id(), "帮张三预订明天的酒店", "request-commit"
        );
        assertEquals(List.of("agent.route", "action.confirm"), eventTypes(confirmation));
        StreamEvent confirmationEvent = confirmation.get(1);
        String taskId = (String) confirmationEvent.payload().get("taskId");
        int confirmationVersion = (Integer) confirmationEvent.payload().get("confirmationVersion");

        List<StreamEvent> completed = orchestrator.confirm(
                "visitor-a", taskId, confirmationVersion, "CONFIRMED", "request-confirm"
        );

        assertEquals("SUCCEEDED", completed.get(2).payload().get("status"));
        assertEquals(
                List.of("agent.route", "action.confirm", "message.final", "card.render", "task.status"),
                orchestrator.events("visitor-a", conversation.id(), 0).stream().map(StreamEvent::type).toList()
        );
        BusinessException replay = assertThrows(BusinessException.class, () -> orchestrator.confirm(
                "visitor-a", taskId, confirmationVersion, "CONFIRMED", "request-replay"
        ));
        assertEquals(ErrorCode.TASK_CONFIRMATION_CONFLICT, replay.code());
        assertTrue(orchestrator.events("visitor-a", conversation.id(), confirmationEvent.sequence()).stream()
                .allMatch(event -> event.sequence() > confirmationEvent.sequence()));
        assertThrows(BusinessException.class, () -> orchestrator.events("visitor-b", conversation.id(), 0));
    }

    @Test
    void switchesDomainsWithOptimisticRoutingVersionAndRestoresTimeline() {
        Conversation conversation = orchestrator.createConversation("visitor-a");
        orchestrator.message("visitor-a", conversation.id(), "明天上海还有海景房吗", "hotel-request");
        Conversation routed = orchestrator.conversations("visitor-a").getFirst();
        assertEquals("hotel", routed.activeAgentCode());
        assertEquals(1, routed.routingVersion());

        List<StreamEvent> selected = orchestrator.selectAgent(
                "visitor-a", conversation.id(), "tourism", routed.routingVersion(), "select-tourism"
        );
        assertEquals("tourism", selected.getFirst().payload().get("targetAgentCode"));
        BusinessException stale = assertThrows(BusinessException.class, () -> orchestrator.selectAgent(
                "visitor-a", conversation.id(), "sports", routed.routingVersion(), "stale-select"
        ));
        assertEquals(ErrorCode.AGENT_ROUTE_VERSION_CONFLICT, stale.code());

        List<TimelineItem> timeline = orchestrator.timeline("visitor-a", conversation.id(), 0, 200);
        assertTrue(timeline.stream().anyMatch(item -> "agent.route".equals(item.eventType())));
        assertTrue(timeline.stream().anyMatch(item -> "card.render".equals(item.eventType())));
    }

    @Test
    void blocksCrossDomainSwitchWhileConfirmationIsPending() {
        Conversation conversation = orchestrator.createConversation("visitor-a");
        orchestrator.message("visitor-a", conversation.id(), "帮张三预订明天的酒店", "blocked-request-commit");
        Conversation routed = orchestrator.conversations("visitor-a").getFirst();

        BusinessException blocked = assertThrows(BusinessException.class, () -> orchestrator.selectAgent(
                "visitor-a", conversation.id(), "tourism", routed.routingVersion(), "blocked-select"
        ));
        assertEquals(ErrorCode.AGENT_SWITCH_BLOCKED_BY_PENDING_ACTION, blocked.code());
    }

    private List<String> eventTypes(List<StreamEvent> events) {
        return events.stream().map(StreamEvent::type).toList();
    }

    private void assertAgent(StreamEvent event, String agentCode, String actionCode) {
        assertNotNull(event.payload().get("agent"));
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) event.payload().get("agent");
        assertEquals(agentCode, agent.get("code"));
        assertEquals(actionCode, event.payload().get("actionCode"));
    }
}
