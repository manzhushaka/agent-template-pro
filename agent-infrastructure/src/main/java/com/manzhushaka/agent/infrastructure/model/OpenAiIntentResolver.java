package com.manzhushaka.agent.infrastructure.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.runtime.intent.DeterministicIntentResolver;
import com.manzhushaka.agent.runtime.intent.IntentDecision;
import com.manzhushaka.agent.runtime.intent.IntentResolver;
import com.manzhushaka.agent.spi.action.ActionDescriptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final DeterministicIntentResolver fallback;

    public OpenAiIntentResolver(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            DeterministicIntentResolver fallback
    ) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    @Override
    public IntentDecision resolve(String content, List<ActionDescriptor> descriptors) {
        try {
            String response = chatModel.call(new Prompt(new UserMessage(prompt(content, descriptors))))
                    .getResult().getOutput().getText();
            Map<String, Object> decision = objectMapper.readValue(response, MAP_TYPE);
            String actionCode = String.valueOf(decision.get("actionCode"));
            if (descriptors.stream().noneMatch(descriptor -> descriptor.code().equals(actionCode))) {
                return fallback.resolve(content, descriptors);
            }
            Object input = decision.get("input");
            return new IntentDecision(
                    actionCode,
                    input instanceof Map<?, ?> values
                            ? values.entrySet().stream().collect(Collectors.toMap(
                                    entry -> String.valueOf(entry.getKey()),
                                    Map.Entry::getValue,
                                    (left, right) -> right,
                                    java.util.LinkedHashMap::new
                            ))
                            : Map.of()
            );
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
