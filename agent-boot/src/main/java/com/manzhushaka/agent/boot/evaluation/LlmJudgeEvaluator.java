package com.manzhushaka.agent.boot.evaluation;

import com.manzhushaka.agent.common.mask.SensitiveMasker;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Controlled LLM judge: input/output are masked before they reach the model, the judge
 * instruction is fixed, and the reply must contain an explicit score. No raw case text is
 * ever persisted or returned.
 */
@Service
@Profile("model-openai")
@ConditionalOnBean(ChatModel.class)
public class LlmJudgeEvaluator implements EvaluatorPlugin {
    private static final Pattern SCORE = Pattern.compile("(?i)(?:score|评分)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)");

    private final ChatModel chatModel;
    private final String model;

    public LlmJudgeEvaluator(
            ChatModel chatModel,
            @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}") String model
    ) {
        this.chatModel = chatModel;
        this.model = model;
    }

    @Override
    public String type() {
        return "LLM_JUDGE";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        String criteria = String.valueOf(context.config().getOrDefault("criteria", "输出是否准确、完整、合规"));
        String maskedInput = SensitiveMasker.maskText(String.valueOf(context.evalCase() == null ? "" : context.evalCase().input()));
        String maskedOutput = SensitiveMasker.maskText(context.outcome() == null ? "" : context.outcome().text());
        String instruction = "你是受控评估器。仅根据给定标准对输出评分，返回一行：score: 0~1 和 reason。"
                + "标准：" + criteria + "。输入（已脱敏）：" + maskedInput + "。输出：" + maskedOutput;
        try {
            String reply = chatModel.call(new Prompt(java.util.List.of(
                    new SystemMessage("你只输出评分，不重复用户输入，不输出任何敏感字段。"),
                    new UserMessage(instruction)
            ))).getResult().getOutput().getText();
            Matcher matcher = SCORE.matcher(reply == null ? "" : reply);
            if (!matcher.find()) {
                return EvaluatorResult.fail(context.evaluatorCode(), type(), "模型未返回可解析评分");
            }
            double score = Math.clamp(Double.parseDouble(matcher.group(1)), 0.0, 1.0);
            double threshold = context.config().get("minScore") instanceof Number number
                    ? number.doubleValue() : 0.6;
            boolean passed = score >= threshold;
            return new EvaluatorResult(
                    context.evaluatorCode(), type(), passed,
                    BigDecimal.valueOf(score), "LLM Judge 评分完成", java.util.Map.of()
            );
        } catch (Exception exception) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "LLM Judge 调用失败");
        }
    }
}
