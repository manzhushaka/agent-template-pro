package com.manzhushaka.agent.runtime.chat;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Stable local fallback used only when no configured model can generate the coordinator reply. */
@Service
@Order(100)
public class PresetCoordinatorConversationResponder implements CoordinatorConversationResponder {
    @Override
    public CoordinatorConversationReply respond(CoordinatorConversationRequest request) {
        String normalized = request.content().toLowerCase(Locale.ROOT);
        String content;
        if (normalized.contains("大模型") || normalized.contains("什么模型") || normalized.contains("模型吗")) {
            content = "当前这次回答没有使用可用的大模型，而是由本地预设回复降级生成。请检查模型 Profile、访问密钥和模型服务状态。";
        } else if (!request.clarificationCandidates().isEmpty()) {
            String choices = request.availableAgents().stream()
                    .filter(agent -> request.clarificationCandidates().contains(agent.code()))
                    .map(agent -> agent.displayName())
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("相关专业服务");
            content = "我还不能确定你需要哪项服务。你想咨询的是" + choices + "中的哪一项？";
        } else if (List.of("聊天", "聊聊", "闲聊", "说说话", "陪我", "陪聊", "聊一聊")
                .stream().anyMatch(normalized::contains)) {
            content = "当然可以，我就在这里陪你聊。你想聊点什么？";
        } else if (normalized.contains("你好") || normalized.contains("您好")
                || normalized.contains("嗨") || normalized.contains("hello") || normalized.equals("hi")) {
            content = "你好！我是集团总智能体。你可以直接跟我聊天，想聊什么都可以；需要办理具体服务时，再告诉我就好。";
        } else if (normalized.contains("你是谁") || normalized.contains("你是做什么")) {
            content = "我是集团总智能体，平时可以先陪你聊天；需要办理具体集团服务时，我也可以帮你转到合适的专业智能体。";
        } else {
            content = "当前模型服务暂时不可用。你可以换一种说法重试，或直接告诉我需要办理的具体服务。";
        }
        return new CoordinatorConversationReply(content, CoordinatorConversationReply.PRESET_FALLBACK);
    }
}
