package com.manzhushaka.agent.infrastructure.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.manzhushaka.agent.runtime.intent.DeterministicIntentResolver;
import com.manzhushaka.agent.runtime.intent.IntentDecision;
import com.manzhushaka.agent.runtime.intent.IntentResolver;
import com.manzhushaka.agent.runtime.model.ModelActionDecision;
import com.manzhushaka.agent.runtime.model.ModelActionSelectionPolicy;
import com.manzhushaka.agent.spi.action.ActionDescriptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * OpenAI-compatible model adapter. Its output is constrained to registered action codes and
 * falls back to deterministic parsing on transport or structured-output failures.
 */
@Service
@Profile("model-openai")
@ConditionalOnBean(ChatModel.class)
@Order(10)
public class OpenAiIntentResolver implements IntentResolver {
    private final ChatModel chatModel;
    private final DeterministicIntentResolver fallback;
    private final ModelActionSelectionPolicy selectionPolicy = new ModelActionSelectionPolicy();

    public OpenAiIntentResolver(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            DeterministicIntentResolver fallback
    ) {
        this.chatModel = chatModel;
        this.fallback = fallback;
    }

    @Override
    public IntentDecision resolve(String content, List<ActionDescriptor> descriptors) {
        try {
            ReactAgent agent = ReactAgent.builder()
                    .name("runtime-intent-extractor")
                    .model(chatModel)
                    .outputType(ModelActionDecision.class)
                    .build();
            AssistantMessage response = agent.call(
                    prompt(content, descriptors),
                    RunnableConfig.builder().threadId("intent-" + UUID.randomUUID()).build()
            );
            ModelActionDecision decision = new BeanOutputConverter<>(ModelActionDecision.class)
                    .convert(response.getText());
            if (decision == null) {
                return fallback.resolve(content, descriptors);
            }
            selectionPolicy.select(decision.actionCode(), descriptors);
            return new IntentDecision(decision.actionCode(), decision.input());
        } catch (Exception exception) {
            return fallback.resolve(content, descriptors);
        }
    }

    private String prompt(String content, List<ActionDescriptor> descriptors) {
        String actions = descriptors.stream()
                .map(descriptor -> descriptor.code() + " required=" + descriptor.requiredFields())
                .collect(Collectors.joining("; "));
        return "Return JSON only, with actionCode and input. Select only one listed action. "
                + "Actions: " + actions + ". User message: " + content;
    }
}
