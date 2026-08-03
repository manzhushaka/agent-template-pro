package com.manzhushaka.agent.controlplane.evaluation.plugin;

import com.manzhushaka.agent.controlplane.evaluation.EvaluatorContext;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorPlugin;
import com.manzhushaka.agent.controlplane.evaluation.EvaluatorResult;
import org.springframework.stereotype.Service;

import java.util.Locale;

/** KNOWLEDGE_CITATION: retrieval must happen and the final text must cite a source marker. */
@Service
public class KnowledgeCitationEvaluator implements EvaluatorPlugin {
    @Override
    public String type() {
        return "KNOWLEDGE_CITATION";
    }

    @Override
    public EvaluatorResult evaluate(EvaluatorContext context) {
        if (context.outcome() == null) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "缺少运行结果");
        }
        if (context.outcome().knowledgeMatchIds().isEmpty()) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "未召回知识");
        }
        String text = context.outcome().text();
        if (text == null || (!text.toLowerCase(Locale.ROOT).contains("[source:")
                && !text.toLowerCase(Locale.ROOT).contains("来源"))) {
            return EvaluatorResult.fail(context.evaluatorCode(), type(), "回复未包含来源引用");
        }
        return EvaluatorResult.pass(context.evaluatorCode(), type(), "知识引用正确");
    }
}
