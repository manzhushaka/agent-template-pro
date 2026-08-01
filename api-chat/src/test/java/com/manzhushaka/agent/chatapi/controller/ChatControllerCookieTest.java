package com.manzhushaka.agent.chatapi.controller;

import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.identity.VisitorCookie;
import com.manzhushaka.agent.runtime.identity.VisitorIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatControllerCookieTest {

    @Test
    void marksVisitorCookieSecureBehindHttpsProxy() {
        ChatOrchestrator orchestrator = mock(ChatOrchestrator.class);
        when(orchestrator.registeredAgents()).thenReturn(List.of());
        VisitorIdentityService identity = mock(VisitorIdentityService.class);
        when(identity.resolve(null)).thenReturn("visitor-1");
        when(identity.cookie("visitor-1")).thenReturn(new VisitorCookie("agent_visitor", "signed", 60));
        ChatController controller = new ChatController(orchestrator, identity);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .get("/api/chat/v1/bootstrap")
                .header("X-Forwarded-Proto", "https")
                .build());

        controller.bootstrap(exchange);

        assertThat(exchange.getResponse().getCookies().getFirst("agent_visitor"))
                .isNotNull()
                .satisfies(cookie -> assertThat(cookie.isSecure()).isTrue());
    }
}
