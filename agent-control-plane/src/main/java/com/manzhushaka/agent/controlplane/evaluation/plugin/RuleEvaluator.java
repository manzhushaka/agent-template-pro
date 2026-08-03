package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** RULE: checks that a named outcome field contains/not-contains/equals a value. */
@Service
public class RuleEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "RULE";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        String field = EvaluationSupport.text(context.config(), "field");
        String operation = EvaluationSupport.text(context.config(), "op");
        String expected = EvaluationSupport.text(context.config(), "value");
        if (field == null || operation == null || expected == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "配置缺少 field/op/value");
        }
        String actual = resolveField(context, field);
        if ("contains".equalsIgnoreCase(operation)) {
            boolean passed = actual != null && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            return passed ? EvaluatorResult.pass(context.evaluatorCode(), type(), "包含规则通过")
                    : EvaluatorResult.fail(context.evaluatorCode(), type(), "未包含期望值");
        }
        if ("not_contains".equalsIgnoreCase(operation)) {
            boolean passed = actual == null || !actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
            return passed ? EvaluatorResult.pass(context.evaluatorCode(), type(), "排除规则通过")
                    : EvaluatorResult.fail(context.evaluatorCode(), type(), "包含禁止内容");
        }
        if ("equals".equalsIgnoreCase(operation)) {
            boolean passed = EvaluationSupport.matches(actual, expected);
            return passed ? EvaluatorResult.pass(context.evaluatorCode(), type(), "相等规则通过")
                    : EvaluatorResult.fail(context.evaluatorCode(), type(), "值不相等");
        }
        return EvaluatorResult.fail(context.evaluatorCode(), type(), "不支持的 op: " + operation);
    }

    static String resolveField(EvaluatorContext context, String field) {
        return switch (field == null ? "" : field.trim().toLowerCase(Locale.ROOT)) {
            case "text", "output" -> context.outcome() == null ? null : context.outcome().text();
            case "route_agent_code" -> context.outcome() == null ? null : context.outcome().routeAgentCode();
            case "route_source" -> context.outcome() == null ? null : context.outcome().routeSource();
            case "error_code" -> context.outcome() == null ? null : context.outcome().errorCode();
            default -> null;
        };
    }
}
