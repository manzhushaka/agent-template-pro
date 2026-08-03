package com.manzhushaka.agent.boot.recovery;

import com.manzhushaka.agent.runtime.recovery.TaskRecoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Runs bounded recovery queries without exposing or repeating the original task write path. */
@Component
@ConditionalOnProperty(prefix = "agent.recovery", name = "enabled", havingValue = "true")
public class TaskRecoveryScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRecoveryScheduler.class);

    private final TaskRecoveryService taskRecoveryService;
    private final int limit;

    public TaskRecoveryScheduler(
            TaskRecoveryService taskRecoveryService,
            @Value("${agent.recovery.limit:50}") int limit
    ) {
        this.taskRecoveryService = taskRecoveryService;
        this.limit = limit;
    }

    @Scheduled(
            initialDelayString = "${agent.recovery.initial-delay:PT30S}",
            fixedDelayString = "${agent.recovery.fixed-delay:PT1M}"
    )
    public void recoverDueTasks() {
        try {
            taskRecoveryService.recoverDue(Instant.now(), limit);
        } catch (RuntimeException exception) {
            LOGGER.error(
                    "Scheduled task recovery pass failed; the next pass will continue (type={})",
                    exception.getClass().getSimpleName()
            );
        }
    }
}
