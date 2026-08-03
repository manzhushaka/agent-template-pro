package com.manzhushaka.agent.infrastructure.model;

import com.manzhushaka.agent.runtime.chat.CoordinatorConversationReply;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationRequest;
import com.manzhushaka.agent.runtime.chat.CoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.chat.CoordinatorPromptBuilder;
import com.manzhushaka.agent.runtime.chat.PresetCoordinatorConversationResponder;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@Profile("model-openai")
@ConditionalOnBean(ChatModel.class)
@Order(10)
public class OpenAiCoordinatorConversationResponder implements CoordinatorConversationResponder {
    private final ChatModel chatModel;
    private final String model;
    private final PresetCoordinatorConversationResponder fallback;
    private final TraceRecorder traceRecorder;

    public OpenAiCoordinatorConversationResponder(
            ChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model,
            PresetCoordinatorConversationResponder fallback,
            TraceRecorder traceRecorder
    ) {
        this.chatModel = chatModel;
        this.model = model;
        this.fallback = fallback;
        this.traceRecorder = traceRecorder;
    }

    @Override
    public CoordinatorConversationReply respond(CoordinatorConversationRequest request) {
        Instant startedAt = Instant.now();
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(CoordinatorPromptBuilder.systemInstruction(request, model)),
                    new UserMessage(CoordinatorPromptBuilder.conversationInput(request))
            ));
            ChatResponse response = chatModel.call(prompt);
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int completionTokens = usage == null || usage.getCompletionTokens() == null
                    ? 0 : usage.getCompletionTokens();
            recordModel(request, startedAt, promptTokens, completionTokens, SpanStatus.OK, null);
            String content = response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                return fallback.respond(request);
            }
            return new CoordinatorConversationReply(content.trim(), CoordinatorConversationReply.MODEL);
        } catch (Exception exception) {
            recordModel(request, startedAt, 0, 0, SpanStatus.ERROR, "MODEL_CALL_FAILED");
            return fallback.respond(request);
        }
    }

    private void recordModel(
            CoordinatorConversationRequest request,
            Instant startedAt,
            int promptTokens,
            int completionTokens,
            SpanStatus status,
            String errorCode
    ) {
        traceRecorder.recordModel(
                request.traceId() == null ? request.requestId() : request.traceId(),
                request.visitorId(), request.conversationId(), request.requestId(),
                "openai", model, promptTokens, completionTokens, status, errorCode,
                startedAt, Instant.now()
        );
    }
}
