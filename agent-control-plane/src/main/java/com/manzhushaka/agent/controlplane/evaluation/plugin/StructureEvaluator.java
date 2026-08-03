package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** STRUCTURE: output text must be valid JSON and contain all configured required fields. */
@Service
public class StructureEvaluator implements EvaluatorPlugin {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String type() {
        return "STRUCTURE";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        Object requiredValue = context.config().get("requiredFields");
        List<String> required = requiredValue instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (context.outcome() == null || context.outcome().text() == null || context.outcome().text().isBlank()) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "输出为空，无法校验结构");
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(context.outcome().text(), Map.class);
            for (String field : required) {
                if (parsed.get(field) == null) {
                    return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少必填字段: " + field);
                }
            }
            return EvaluatorResult.pass(context.evaluatorCode(), type(), "JSON 结构校验通过");
        } catch (Exception exception) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "输出不是合法 JSON");
        }
    }
}
