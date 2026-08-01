package com.manzhushaka.agent.boot;

import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationReply;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationRequest;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.event.StreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(CoordinatorConversationResponderTest.ModelResponderConfiguration.class)
class CoordinatorConversationResponderTest {
    @Autowired
    private ChatOrchestrator orchestrator;

    @Autowired
    private AtomicReference<CoordinatorConversationRequest> capturedRequest;

    @Test
    void generalConversationUsesModelResponderWithRecentHistoryAndCapabilities() {
        Conversation conversation = orchestrator.createConversation("visitor-model-response");
        orchestrator.message(
                "visitor-model-response", conversation.id(), "你好，我叫小王", "model-response-1"
        );

        List<StreamEvent> response = orchestrator.message(
                "visitor-model-response", conversation.id(), "根据刚才的对话介绍一下你自己", "model-response-2"
        );

        assertEquals("这是模型结合上下文生成的回答。", response.getFirst().payload().get("content"));
        assertEquals("MODEL", response.getFirst().payload().get("generationSource"));
        CoordinatorConversationRequest request = capturedRequest.get();
        assertEquals("根据刚才的对话介绍一下你自己", request.content());
        assertTrue(request.history().stream().anyMatch(message -> message.content().contains("小王")));
        assertTrue(request.availableAgents().stream().anyMatch(agent -> "hotel".equals(agent.code())));
    }

    @TestConfiguration
    static class ModelResponderConfiguration {
        @Bean
        AtomicReference<CoordinatorConversationRequest> capturedRequest() {
            return new AtomicReference<>();
        }

        @Bean
        @Order(1)
        CoordinatorConversationResponder testModelResponder(
                AtomicReference<CoordinatorConversationRequest> capturedRequest
        ) {
            return request -> {
                capturedRequest.set(request);
                return new CoordinatorConversationReply(
                        "这是模型结合上下文生成的回答。",
                        CoordinatorConversationReply.MODEL
                );
            };
        }
    }
}
