package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

/** CLARIFICATION: expects the runtime to ask a follow-up instead of executing. */
@Service
public class ClarificationEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "CLARIFICATION";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        if (context.outcome() == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少运行结果");
        }
        if (context.outcome().clarificationRequested()) {
            return EvaluatorResult.pass(context.evaluatorCode(), type(), "触发追问");
        }
        return EvaluatorResult.fail(context.evaluatorCode(), type(), "未触发追问");
    }
}
