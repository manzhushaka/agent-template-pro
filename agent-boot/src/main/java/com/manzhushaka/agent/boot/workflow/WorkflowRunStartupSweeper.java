package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.runtime.workflow.WorkflowExecutionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Marks runs left RUNNING by a crashed process as PAUSED so they can be resumed manually. */
@Component
public class WorkflowRunStartupSweeper {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRunStartupSweeper.class);

    private final WorkflowExecutionEngine engine;

    public WorkflowRunStartupSweeper(WorkflowExecutionEngine engine) {
        this.engine = engine;
    }

    @Scheduled(initialDelayString = "${agent.workflow.sweep-initial-delay:PT15S}",
            fixedDelayString = "${agent.workflow.sweep-fixed-delay:PT60S}")
    public void sweep() {
        try {
            int recovered = engine.sweepStaleRuns().size();
            if (recovered > 0) {
                LOGGER.info("工作流启动恢复：{} 个 RUNNING 运行已置为 PAUSED 等待手动恢复。", recovered);
            }
        } catch (Exception exception) {
            LOGGER.warn("工作流 stale run 扫描失败", exception);
        }
    }
}
