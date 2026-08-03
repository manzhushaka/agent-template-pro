package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/** TOOL_SELECTION: expects a specific tool to have been executed. */
@Service
public class ToolSelectionEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "TOOL_SELECTION";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        Map<String, Object> expected = context.evalCase() == null ? Map.of() : context.evalCase().expected();
        Object toolCode = expected.get("toolCode");
        if (toolCode == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "expected.toolCode 缺失");
        }
        if (context.outcome() == null || !context.outcome().toolCodes().contains(String.valueOf(toolCode))) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "未执行期望工具");
        }
        return EvaluatorResult.pass(context.evaluatorCode(), type(), "工具选择正确");
    }
}
