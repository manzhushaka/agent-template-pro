package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.event.StreamEvent;
import com.manzhushaka.agent.controlplane.AgentApplicationService;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM node: interpolates the inline prompt with run variables and calls the configured ChatModel.
 * Without a configured model the node returns a deterministic PRESET_FALLBACK text (same policy as
 * chat), and the MODEL span still records the attempt.
 */
@Component
public class WorkflowLlmHandler implements WorkflowNodeHandler {
    private static final String SYSTEM_INSTRUCTION =
            "你是 agent-pro 工作流中的一个确定性文本生成节点。只输出最终内容，不要输出 JSON 或解释。";

    private final ObjectProvider<ChatModel> chatModel;
    private final TraceRecorder traceRecorder;
    private final AgentApplicationService applicationService;

    public WorkflowLlmHandler(
            ObjectProvider<ChatModel> chatModel,
            TraceRecorder traceRecorder,
            AgentApplicationService applicationService
    ) {
        this.chatModel = chatModel;
        this.traceRecorder = traceRecorder;
        this.applicationService = applicationService;
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.LLM;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        String outputVar = string(context.node().config().get("outputVar"));
        String prompt;
        if (Boolean.TRUE.equals(context.node().config().get("useBoundPrompt"))) {
            String promptVersionId = binding(context, "promptVersionId");
            if (promptVersionId.isBlank()) {
                return WorkflowNodeResult.failed(context.variables(), "PROMPT_BINDING_MISSING");
            }
            prompt = applicationService.resolvePromptContent(promptVersionId, context.variables());
        } else {
            prompt = interpolate(string(context.node().config().get("prompt")), context.variables());
        }
        Instant startedAt = Instant.now();
        ChatModel model = chatModel.getIfAvailable();
        String text;
        String provider = "preset-fallback";
        String modelName = "deterministic";
        if (model == null) {
            text = "当前工作流 LLM 节点未启用模型服务，使用确定性回退生成。请求提示词摘要：" + prompt;
            if (text.length() > 1200) {
                text = text.substring(0, 1200);
            }
            traceRecorder.recordModel(context.requestId(), context.visitorRef(), null, context.requestId(),
                    provider, modelName, 0, 0, SpanStatus.OK, null, startedAt, Instant.now());
        } else {
            try {
                ChatResponse response = model.call(new Prompt(
                        List.of(new SystemMessage(SYSTEM_INSTRUCTION), new UserMessage(prompt))
                ));
                String content = response.getResult() == null || response.getResult().getOutput() == null
                        ? null : response.getResult().getOutput().getText();
                if (content == null || content.isBlank()) {
                    text = "模型返回了空回复。";
                } else {
                    text = content.trim();
                }
                provider = "chat-model";
                modelName = model.getDefaultOptions() == null ? "configured" : String.valueOf(model.getDefaultOptions().getModel());
                traceRecorder.recordModel(context.requestId(), context.visitorRef(), null, context.requestId(),
                        provider, modelName, 0, 0, SpanStatus.OK, null, startedAt, Instant.now());
            } catch (RuntimeException exception) {
                text = "当前工作流 LLM 节点调用模型失败，使用确定性回退生成。请求提示词摘要：" + prompt;
                if (text.length() > 1200) {
                    text = text.substring(0, 1200);
                }
                traceRecorder.recordModel(context.requestId(), context.visitorRef(), null, context.requestId(),
                        provider, modelName, 0, 0, SpanStatus.ERROR, "MODEL_CALL_FAILED", startedAt, Instant.now());
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>(context.variables());
        variables.put(outputVar, text);
        StreamEvent event = new StreamEvent(
                "message.final", context.run().id(), context.requestId(), 0, Instant.now(),
                Map.of("content", text, "generationSource",
                        "preset-fallback".equals(provider) ? "PRESET_FALLBACK" : "MODEL")
        );
        return WorkflowNodeResult.succeeded(Map.of(outputVar, text), variables, List.of(event));
    }

    private static String interpolate(String prompt, Map<String, Object> variables) {
        if (prompt == null || prompt.isBlank()) {
            return "";
        }
        String result = prompt;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static String binding(WorkflowNodeContext context, String key) {
        Object bindingsValue = context.variables().get("_workflowBindings");
        if (bindingsValue instanceof Map<?, ?> bindings) {
            Object value = bindings.get(key);
            return value == null ? "" : String.valueOf(value);
        }
        return "";
    }
}
