package com.manzhushaka.agent.controlplane.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Restart-recoverable evaluation worker. It claims one running experiment per tick; a lease
 * on the experiment row lets any instance resume pending cases after a crash.
 */
@Service
public class EvaluationWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(EvaluationWorker.class);

    private final EvaluationService evaluationService;
    private final boolean enabled;

    public EvaluationWorker(
            EvaluationService evaluationService,
            @Value("${agent.evaluation.worker-enabled:true}") boolean enabled
    ) {
        this.evaluationService = evaluationService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${agent.evaluation.worker-fixed-delay:PT5S}", initialDelayString = "${agent.evaluation.worker-initial-delay:PT10S}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            evaluationService.processNext("eval-worker");
        } catch (Exception exception) {
            LOGGER.warn("Evaluation worker tick failed", exception);
        }
    }
}
