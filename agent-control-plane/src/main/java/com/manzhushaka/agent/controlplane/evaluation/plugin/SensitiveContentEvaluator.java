package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

/** SENSITIVE: refuses when the produced output leaks phone/card/email/credential patterns. */
@Service
public class SensitiveContentEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "SENSITIVE";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        String text = context.outcome() == null ? null : context.outcome().text();
        if (EvaluationSupport.textClean(text)) {
            return EvaluatorResult.pass(context.evaluatorCode(), type(), "输出未包含敏感内容");
        }
        return EvaluatorResult.fail(context.evaluatorCode(), type(), "输出包含疑似敏感内容");
    }
}
