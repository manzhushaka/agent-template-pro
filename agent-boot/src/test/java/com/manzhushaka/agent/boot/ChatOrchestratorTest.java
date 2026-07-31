package com.manzhushaka.agent.boot;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class ChatOrchestratorTest {
    @Autowired private ChatOrchestrator orchestrator;

    @Test
    void collectsMissingInputThenExecutesOnlyForTheOwningVisitor() {
        Conversation conversation = orchestrator.createConversation("visitor-a");
        List<StreamEvent> requested = orchestrator.message("visitor-a", conversation.id(), "查询天气", "request-1");
        assertEquals("form.request", requested.getFirst().type());
        String pendingId = (String) requested.getFirst().payload().get("pendingActionId");

        List<StreamEvent> result = orchestrator.submitInput("visitor-a", pendingId, Map.of("city", "上海"), "request-2");
        assertEquals("message.final", result.getFirst().type());
        assertEquals("card.render", result.get(1).type());
        assertThrows(BusinessException.class, () -> orchestrator.messages("visitor-b", conversation.id()));
    }
}
