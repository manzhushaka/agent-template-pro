package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/** PARAM_EXTRACTION: expects the runtime to request all configured required fields. */
@Service
public class ParamExtractionEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "PARAM_EXTRACTION";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        Map<String, Object> expected = context.evalCase() == null ? Map.of() : context.evalCase().expected();
        Object fieldsValue = expected.get("requiredFields");
        if (!(fieldsValue instanceof java.util.List<?> required)) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "expected.requiredFields 缺失");
        }
        if (context.outcome() == null || context.outcome().formFields() == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少补参结果");
        }
        for (Object field : required) {
            if (context.outcome().formFields().stream().noneMatch(
                    actual -> actual.equalsIgnoreCase(String.valueOf(field)))) {
                return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少字段: " + field);
            }
        }
        return EvaluatorResult.pass(context.evaluatorCode(), type(), "参数提取完整");
    }
}
