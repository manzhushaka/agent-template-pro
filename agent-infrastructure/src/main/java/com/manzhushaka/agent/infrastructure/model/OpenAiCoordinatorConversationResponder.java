package com.manzhushaka.agent.infrastructure.model;

import com.manzhushaka.agent.runtime.chat.CoordinatorConversationReply;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationRequest;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.chat.CoordinatorPromptBuilder;
import com.manzhushaka.agent.runtime.chat.PresetCoordinatorConversationResponder;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Profile("model-openai")
@ConditionalOnBean(ChatModel.class)
@Order(10)
public class OpenAiCoordinatorConversationResponder implements CoordinatorConversationResponder {
    private final ChatModel chatModel;
    private final String model;
    private final PresetCoordinatorConversationResponder fallback;

    public OpenAiCoordinatorConversationResponder(
            ChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            PresetCoordinatorConversationResponder fallback
    ) {
        this.chatModel = chatModel;
        this.model = model;
        this.fallback = fallback;
    }

    @Override
    public CoordinatorConversationReply respond(CoordinatorConversationRequest request) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(CoordinatorPromptBuilder.systemInstruction(request, model)),
                    new UserMessage(CoordinatorPromptBuilder.conversationInput(request))
            ));
            String content = chatModel.call(prompt).getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                return fallback.respond(request);
            }
            return new CoordinatorConversationReply(content.trim(), CoordinatorConversationReply.MODEL);
        } catch (Exception exception) {
            return fallback.respond(request);
        }
    }
}
