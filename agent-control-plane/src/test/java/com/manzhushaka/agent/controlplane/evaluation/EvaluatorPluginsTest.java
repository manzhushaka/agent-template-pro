package com.manzhushaka.agent.controlplane.evaluation;

import com.manzhushaka.agent.controlplane.evaluation.plugin.ClarificationEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.ConfirmationGateEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.DenyEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.IntentRouteEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.KnowledgeCitationEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.ParamExtractionEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.RuleEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.SensitiveContentEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.StructureEvaluator;
import com.manzhushaka.agent.controlplane.evaluation.plugin.ToolSelectionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluatorPluginsTest {
    @Test
    void intentRouteMatchesExpectedAgentAndAction() {
        EvaluatorResult result = new IntentRouteEvaluator().evaluate(context(
                Map.of("agentCode", "hotel", "actionCode", "hotel.room.search"),
                outcome("hotel", "hotel.room.search", false, false, false, null, List.of(), List.of())
        ));
        assertTrue(result.passed());

        EvaluatorResult wrong = new IntentRouteEvaluator().evaluate(context(
                Map.of("agentCode", "hotel"),
                outcome("flight", "hotel.room.search", false, false, false, null, List.of(), List.of())
        ));
        assertFalse(wrong.passed());
    }

    @Test
    void confirmationGateRequiresVersionAndNoPrematureToolExecution() {
        EvaluatorResult result = new ConfirmationGateEvaluator().evaluate(context(
                Map.of("confirmationVersion", 1),
                outcome("hotel", "hotel.order.cancel", true, true, true, 1, List.of(), List.of())
        ));
        assertTrue(result.passed());

        EvaluatorResult premature = new ConfirmationGateEvaluator().evaluate(context(
                Map.of("confirmationVersion", 1),
                outcome("hotel", "hotel.order.cancel", false, false, true, 1,
                        List.of(), List.of("hotel.order.cancel"))
        ));
        assertFalse(premature.passed(), "确认前发生工具执行必须判失败");
    }

    @Test
    void denyPassesOnlyWhenNothingExecuted() {
        assertTrue(new DenyEvaluator().evaluate(context(
                Map.of(),
                outcome(null, null, false, true, false, null, List.of(), List.of())
        )).passed());
        assertFalse(new DenyEvaluator().evaluate(context(
                Map.of(),
                outcome(null, null, false, true, false, null, List.of(), List.of("hotel.order.cancel"))
        )).passed());
    }

    @Test
    void clarificationAndParamExtractionAndToolSelection() {
        assertTrue(new ClarificationEvaluator().evaluate(context(
                Map.of(),
                outcome(null, null, true, false, false, null, List.of(), List.of())
        )).passed());
        assertTrue(new ParamExtractionEvaluator().evaluate(context(
                Map.of("requiredFields", List.of("city", "date")),
                outcome("hotel", "hotel.room.search", false, false, false, null,
                        List.of("city", "date"), List.of())
        )).passed());
        assertFalse(new ParamExtractionEvaluator().evaluate(context(
                Map.of("requiredFields", List.of("city", "guestName")),
                outcome("hotel", "hotel.room.search", false, false, false, null,
                        List.of("city"), List.of())
        )).passed());
        assertTrue(new ToolSelectionEvaluator().evaluate(context(
                Map.of("toolCode", "hotel.room.search"),
                outcome("hotel", "hotel.room.search", false, false, false, null,
                        List.of(), List.of("hotel.room.search"))
        )).passed());
    }

    @Test
    void knowledgeCitationRequiresRetrievalAndSourceMarker() {
        assertTrue(new KnowledgeCitationEvaluator().evaluate(context(
                Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(),
                        List.of(), List.of("kb:kb-1"), "根据来源 [source:doc-1] 回答")
        )).passed());
        assertFalse(new KnowledgeCitationEvaluator().evaluate(context(
                Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(),
                        List.of(), List.of("kb:kb-1"), "已为您查询，未附引用标注")
        )).passed());
    }

    @Test
    void ruleStructureAndSensitiveChecks() {
        EvaluatorContext ruleContext = contextWithConfig(
                Map.of("field", "text", "op", "contains", "value", "酒店"),
                Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(), List.of(), "酒店房态已查询"));
        assertTrue(new RuleEvaluator().evaluate(ruleContext).passed());

        EvaluatorContext structureContext = contextWithConfig(
                Map.of("requiredFields", List.of("code", "name")),
                Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(), List.of(),
                        "{\"code\":\"ok\",\"name\":\"n\"}"));
        assertTrue(new StructureEvaluator().evaluate(structureContext).passed());

        EvaluatorContext leakContext = context(Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(), List.of(),
                        "联系 13812345678"));
        assertFalse(new SensitiveContentEvaluator().evaluate(leakContext).passed());
        assertTrue(new SensitiveContentEvaluator().evaluate(context(Map.of(),
                outcome("hotel", null, false, false, false, null, List.of(), List.of(), "正常回复"))).passed());
    }

    private static EvaluatorContext context(Map<String, Object> expected, EvaluationRunOutcome outcome) {
        return contextWithConfig(Map.of(), expected, outcome);
    }

    private static EvaluatorContext contextWithConfig(
            Map<String, Object> config,
            Map<String, Object> expected,
            EvaluationRunOutcome outcome
    ) {
        EvalCase evalCase = new EvalCase(
                "ecs-test", "eds-test", "edv-test", "case-key", "test",
                Map.of("text", "输入"), expected, Map.of(), "MANUAL", null, "tester", java.time.Instant.now()
        );
        return new EvaluatorContext("eevv-test", "eval-code", "EVAL", config, evalCase, outcome);
    }

    private static EvaluationRunOutcome outcome(
            String agentCode,
            String actionCode,
            boolean clarification,
            boolean refused,
            boolean taskCreated,
            Integer confirmationVersion,
            List<String> formFields,
            List<String> toolCodes
    ) {
        return outcome(agentCode, actionCode, clarification, refused, taskCreated,
                confirmationVersion, formFields, toolCodes, "");
    }

    private static EvaluationRunOutcome outcome(
            String agentCode,
            String actionCode,
            boolean clarification,
            boolean refused,
            boolean taskCreated,
            Integer confirmationVersion,
            List<String> formFields,
            List<String> toolCodes,
            String text
    ) {
        return outcome(agentCode, actionCode, clarification, refused, taskCreated,
                confirmationVersion, formFields, toolCodes, List.of(), text);
    }

    private static EvaluationRunOutcome outcome(
            String agentCode,
            String actionCode,
            boolean clarification,
            boolean refused,
            boolean taskCreated,
            Integer confirmationVersion,
            List<String> formFields,
            List<String> toolCodes,
            List<String> knowledgeMatchIds,
            String text
    ) {
        boolean confirmationRequired = confirmationVersion != null;
        return new EvaluationRunOutcome(
                text, List.of("message.final"), agentCode, actionCode, "MODEL", "DOMAIN_AGENT",
                formFields, clarification, refused, taskCreated, confirmationRequired,
                confirmationVersion, toolCodes, knowledgeMatchIds, formFields, 0, 0L, null
        );
    }
}
