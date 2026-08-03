package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.Map;

/** INTENT_ROUTE: expects the runtime to route to a specific agent/action. */
@Service
public class IntentRouteEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "INTENT_ROUTE";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        Map<String, Object> expected = context.evalCase() == null ? Map.of() : context.evalCase().expected();
        String expectedAgent = EvaluationSupport.text(expected, "agentCode");
        String expectedAction = EvaluationSupport.text(expected, "actionCode");
        if (context.outcome() == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少运行结果");
        }
        if (expectedAgent != null && !EvaluationSupport.matches(context.outcome().routeAgentCode(), expectedAgent)) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(),
                    "路由目标不匹配: " + context.outcome().routeAgentCode());
        }
        if (expectedAction != null && !EvaluationSupport.matches(context.outcome().actionCode(), expectedAction)) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(),
                    "动作未命中: " + context.outcome().actionCode());
        }
        return EvaluatorResult.pass(context.evaluatorCode(), type(), "意图路由正确");
    }
}
