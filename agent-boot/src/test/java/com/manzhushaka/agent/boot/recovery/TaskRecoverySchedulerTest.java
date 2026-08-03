package com.manzhushaka.agent.boot.recovery;

import com.manzhushaka.agent.runtime.recovery.TaskRecoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TaskRecoverySchedulerTest {
    @Test
    void schedulerIsDisabledByDefault() {
        contextRunner(mock(TaskRecoveryService.class)).run(context ->
                assertThat(context).doesNotHaveBean(TaskRecoveryScheduler.class)
        );
    }

    @Test
    void enabledSchedulerRunsOneBoundedRecoveryPass() {
        TaskRecoveryService recoveryService = mock(TaskRecoveryService.class);

        contextRunner(recoveryService)
                .withPropertyValues(
                        "agent.recovery.enabled=true",
                        "agent.recovery.initial-delay=PT1H",
                        "agent.recovery.fixed-delay=PT1H",
                        "agent.recovery.limit=17"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(TaskRecoveryScheduler.class);

                    context.getBean(TaskRecoveryScheduler.class).recoverDueTasks();

                    verify(recoveryService).recoverDue(any(Instant.class), eq(17));
                    verifyNoMoreInteractions(recoveryService);
                });
    }

    @Test
    void failureInOnePassDoesNotPreventTheNextPass() {
        TaskRecoveryService recoveryService = mock(TaskRecoveryService.class);
        when(recoveryService.recoverDue(any(Instant.class), eq(10)))
                .thenThrow(new IllegalStateException("provider unavailable"))
                .thenReturn(List.of());
        TaskRecoveryScheduler scheduler = new TaskRecoveryScheduler(recoveryService, 10);

        assertDoesNotThrow(scheduler::recoverDueTasks);
        assertDoesNotThrow(scheduler::recoverDueTasks);

        verify(recoveryService, times(2)).recoverDue(any(Instant.class), eq(10));
        verifyNoMoreInteractions(recoveryService);
    }

    private ApplicationContextRunner contextRunner(TaskRecoveryService recoveryService) {
        return new ApplicationContextRunner()
                .withUserConfiguration(TestConfiguration.class)
                .withBean(TaskRecoveryService.class, () -> recoveryService);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(TaskRecoveryScheduler.class)
    static class TestConfiguration {
    }
}
