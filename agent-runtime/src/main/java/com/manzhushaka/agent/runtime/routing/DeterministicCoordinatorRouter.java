package com.manzhushaka.agent.runtime.routing;

import com.manzhushaka.agent.spi.domain.DomainAgentDescriptor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@Order(100)
public class DeterministicCoordinatorRouter implements CoordinatorRouter {
    @Override
    public RouteDecision route(
            String content,
            ConversationRoutingContext context,
            List<DomainAgentDescriptor> candidates
    ) {
        String normalized = content.toLowerCase(Locale.ROOT);
        if (isGeneralAssistance(normalized)) {
            return new RouteDecision(RouteType.GENERAL_ASSISTANCE, null, 1,
                    RouteSource.DETERMINISTIC_FALLBACK, "GROUP_PUBLIC_INFORMATION", List.of());
        }
        if (isConversational(normalized)) {
            return new RouteDecision(RouteType.GENERAL_ASSISTANCE, null, 1,
                    RouteSource.DETERMINISTIC_FALLBACK, "GENERAL_CONVERSATION", List.of());
        }
        List<DomainAgentDescriptor> matches = candidates.stream()
                .filter(candidate -> matches(normalized, candidate))
                .toList();
        if (matches.size() > 1) {
            return new RouteDecision(RouteType.CLARIFICATION_REQUIRED, null, 0.5,
                    RouteSource.DETERMINISTIC_FALLBACK, "MULTIPLE_DOMAIN_INTENTS",
                    matches.stream().map(DomainAgentDescriptor::code).toList());
        }
        if (matches.size() == 1) {
            String target = matches.getFirst().code();
            RouteType type = target.equals(context.currentAgentCode())
                    ? RouteType.KEEP_CURRENT_AGENT
                    : RouteType.DOMAIN_AGENT;
            RouteSource source = type == RouteType.KEEP_CURRENT_AGENT
                    ? RouteSource.CURRENT_CONTEXT
                    : RouteSource.DETERMINISTIC_FALLBACK;
            return new RouteDecision(type, target, 0.95, source,
                    "DOMAIN_HINT_MATCHED", List.of());
        }
        if (context.currentAgentCode() != null && isContextual(normalized)) {
            return new RouteDecision(RouteType.KEEP_CURRENT_AGENT, context.currentAgentCode(), 0.8,
                    RouteSource.CURRENT_CONTEXT, "CURRENT_AGENT_CONTEXT", List.of());
        }
        return new RouteDecision(RouteType.GENERAL_ASSISTANCE, null, 0.85,
                RouteSource.DETERMINISTIC_FALLBACK, "GENERAL_CONVERSATION", List.of());
    }

    private boolean matches(String content, DomainAgentDescriptor candidate) {
        List<String> hints = new ArrayList<>(candidate.routingHints());
        hints.add(candidate.code());
        hints.add(candidate.displayName());
        return hints.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(content::contains);
    }

    private boolean isGeneralAssistance(String content) {
        return List.of("客服电话", "客服热线", "隐私", "人工客服", "怎么联系")
                .stream().anyMatch(content::contains);
    }

    private boolean isConversational(String content) {
        return List.of("聊天", "聊聊", "闲聊", "说说话", "陪我", "陪聊", "聊一聊")
                .stream().anyMatch(content::contains);
    }

    private boolean isContextual(String content) {
        return List.of("这个", "那个", "刚才", "继续", "它", "订单", "详情")
                .stream().anyMatch(content::contains);
    }
}
