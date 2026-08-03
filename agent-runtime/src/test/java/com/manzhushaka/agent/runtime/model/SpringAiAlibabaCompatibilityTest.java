package com.manzhushaka.agent.runtime.model;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.manzhushaka.agent.spi.action.ActionDescriptor;
import com.manzhushaka.agent.spi.action.ActionMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiAlibabaCompatibilityTest {
    @Test
    void graphSupportsRunnableConfigStreamingAndCheckpoint() throws Exception {
        MemorySaver saver = MemorySaver.builder().build();
        CompileConfig compileConfig = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .build();
        StateGraph stateGraph = new StateGraph(() -> Map.of("answer", new ReplaceStrategy()))
                .addNode("answer", node_async(state -> Map.of(
                        "answer", "processed:" + state.value("input").orElseThrow()
                )))
                .addEdge(START, "answer")
                .addEdge("answer", END);
        CompiledGraph graph = stateGraph.compile(compileConfig);
        RunnableConfig config = RunnableConfig.builder()
                .threadId("m0-graph-thread")
                .streamMode(CompiledGraph.StreamMode.SNAPSHOTS)
                .build();

        List<?> outputs = graph.stream(Map.of("input", "hello"), config).collectList().block();

        assertNotNull(outputs);
        assertFalse(outputs.isEmpty());
        assertEquals("processed:hello", graph.getState(config).state().value("answer").orElseThrow());
        assertFalse(saver.list(config).isEmpty());
    }

    @Test
    void reactAgentRunsToolAndSupportsCallAndFluxWithoutNetwork() throws Exception {
        StubToolCallingChatModel model = new StubToolCallingChatModel();
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback tool = FunctionToolCallback.builder("safeLookup", (LookupInput input) -> {
                    toolCalls.incrementAndGet();
                    return "found:" + input.query();
                })
                .description("Looks up public demo data")
                .inputType(LookupInput.class)
                .build();
        ReactAgent agent = ReactAgent.builder()
                .name("m0-compatibility-agent")
                .model(model)
                .tools(tool)
                .saver(MemorySaver.builder().build())
                .build();

        AssistantMessage direct = agent.call(
                "look up demo data",
                RunnableConfig.builder().threadId("m0-agent-call").build()
        );
        List<Message> streamed = agent.streamMessages(
                "look up demo data",
                RunnableConfig.builder().threadId("m0-agent-stream").build()
        ).collectList().block();

        assertEquals("tool result accepted", direct.getText());
        assertNotNull(streamed);
        assertTrue(streamed.stream().anyMatch(message -> "tool result accepted".equals(message.getText())));
        assertEquals(2, toolCalls.get());
        assertTrue(model.streamCalls.get() > 0);
    }

    @Test
    void modelToolPolicyRejectsEveryHighRiskActionMode() {
        ModelToolExposurePolicy policy = new ModelToolExposurePolicy();

        assertTrue(policy.isModelCallable(ActionMode.QUERY));
        assertTrue(policy.isModelCallable(ActionMode.DRAFT));
        for (ActionMode mode : List.of(ActionMode.COMMIT, ActionMode.PAYMENT, ActionMode.AFTER_SALE)) {
            assertFalse(policy.isModelCallable(mode));
            assertThrows(IllegalArgumentException.class, () -> policy.requireModelCallable(mode));
        }
    }

    @Test
    void reactAgentStructuredOutputMapsOnlyToWhitelistedActionDescriptors() throws Exception {
        StructuredDecisionChatModel model = new StructuredDecisionChatModel();
        ReactAgent agent = ReactAgent.builder()
                .name("m0-structured-routing-agent")
                .model(model)
                .outputType(ModelActionDecision.class)
                .build();

        AssistantMessage output = agent.call(
                "select one allowed action",
                RunnableConfig.builder().threadId("m0-structured-output").build()
        );
        BeanOutputConverter<ModelActionDecision> converter = new BeanOutputConverter<>(ModelActionDecision.class);
        ModelActionDecision decision = Objects.requireNonNull(converter.convert(output.getText()));
        ActionDescriptor allowed = new ActionDescriptor(
                "hotel.room.query",
                "Query rooms",
                ActionMode.QUERY,
                List.of("city"),
                ""
        );
        ModelActionSelectionPolicy selectionPolicy = new ModelActionSelectionPolicy();

        assertTrue(model.promptContainsStructuredSchema());
        assertSame(allowed, selectionPolicy.select(decision.actionCode(), List.of(allowed)));
        assertThrows(
                IllegalArgumentException.class,
                () -> selectionPolicy.select("admin.secret.rotate", List.of(allowed))
        );
    }

    private record LookupInput(String query) {
    }

    private record ModelActionDecision(String actionCode, Map<String, Object> input) {
    }

    private static final class StructuredDecisionChatModel implements ChatModel {
        private Prompt lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            AssistantMessage message = new AssistantMessage(
                    "{\"actionCode\":\"hotel.room.query\",\"input\":{\"city\":\"Shanghai\"}}"
            );
            return new ChatResponse(List.of(new Generation(message)));
        }

        boolean promptContainsStructuredSchema() {
            return lastPrompt != null && lastPrompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .anyMatch(text -> text.contains("actionCode") && text.contains("input"));
        }
    }

    private static final class StubToolCallingChatModel implements ChatModel {
        private final AtomicInteger streamCalls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            boolean hasToolResponse = prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
            AssistantMessage message = hasToolResponse
                    ? new AssistantMessage("tool result accepted")
                    : AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    "tool-call-1", "function", "safeLookup", "{\"query\":\"demo\"}"
                            )))
                            .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            streamCalls.incrementAndGet();
            return Flux.just(call(prompt));
        }
    }
}
