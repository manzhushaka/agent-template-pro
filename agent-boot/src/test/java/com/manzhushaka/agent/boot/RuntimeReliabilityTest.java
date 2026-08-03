package com.manzhushaka.agent.boot;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.manzhushaka.agent.infrastructure.checkpoint.DurableGraphCheckpointSaver;
import com.manzhushaka.agent.runtime.chat.Conversation;
import com.manzhushaka.agent.runtime.recovery.TaskRecoveryOutcome;
import com.manzhushaka.agent.runtime.recovery.TaskRecoveryProbe;
import com.manzhushaka.agent.runtime.recovery.TaskRecoveryResult;
import com.manzhushaka.agent.runtime.recovery.TaskRecoveryService;
import com.manzhushaka.agent.runtime.recovery.TrustedTaskResultService;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.OutboxRecord;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.store.ToolExecutionRecord;
import com.manzhushaka.agent.runtime.store.ToolExecutionStatus;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.ConfirmationSnapshotHasher;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RuntimeReliabilityTest {
    @Autowired
    private RuntimeStore store;

    @Autowired
    private ConfirmationSnapshotHasher snapshotHasher;

    @Autowired
    private BaseCheckpointSaver checkpointSaver;

    @Test
    void confirmationChecksOwnerHashExpiryVersionAndAllowsOnlyOneConcurrentDecision() throws Exception {
        Conversation conversation = store.createConversation("visitor-confirm-" + UUID.randomUUID());
        Map<String, Object> input = Map.of("nested", Map.of("b", 2, "a", 1), "quantity", 1);
        String hash = snapshotHasher.hash("shop.order.commit", input);
        AgentTask task = new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "shop",
                "shop.order.commit", "idem-" + UUID.randomUUID(), input
        );
        task.prepareConfirmation(1, hash, Instant.now().plusSeconds(60));
        task = store.saveTask(task);

        assertFalse(store.decideConfirmation(
                "another-visitor", task.id(), 1, task.version(), hash, ConfirmationDecision.CONFIRMED,
                "wrong-owner", Instant.now(), Instant.now().plusSeconds(30)
        ).isPresent());
        assertFalse(store.decideConfirmation(
                task.visitorId(), task.id(), 1, task.version(), "wrong-hash", ConfirmationDecision.CONFIRMED,
                "wrong-hash", Instant.now(), Instant.now().plusSeconds(30)
        ).isPresent());

        AgentTask candidate = task;
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> decisions = List.of(
                    () -> store.decideConfirmation(
                            candidate.visitorId(), candidate.id(), 1, candidate.version(), hash,
                            ConfirmationDecision.CONFIRMED, "confirm-a", Instant.now(), Instant.now().plusSeconds(30)
                    ).isPresent(),
                    () -> store.decideConfirmation(
                            candidate.visitorId(), candidate.id(), 1, candidate.version(), hash,
                            ConfirmationDecision.CONFIRMED, "confirm-b", Instant.now(), Instant.now().plusSeconds(30)
                    ).isPresent()
            );
            long successfulDecisions = 0;
            for (var future : executor.invokeAll(decisions)) {
                if (future.get()) {
                    successfulDecisions++;
                }
            }
            assertEquals(1, successfulDecisions);
        }
        assertEquals(TaskStatus.DISPATCHED, store.findTask(task.visitorId(), task.id()).orElseThrow().status());
    }

    @Test
    void expiredConfirmationCannotExecuteAndCanOnlyBecomeExpired() {
        Conversation conversation = store.createConversation("visitor-expiry-" + UUID.randomUUID());
        Map<String, Object> input = Map.of("bookingNo", "DEMO-1");
        String hash = snapshotHasher.hash("hotel.booking.cancel", input);
        AgentTask task = new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "hotel",
                "hotel.booking.cancel", "idem-" + UUID.randomUUID(), input
        );
        task.prepareConfirmation(1, hash, Instant.now().minusSeconds(1));
        task = store.saveTask(task);

        assertFalse(store.decideConfirmation(
                task.visitorId(), task.id(), 1, task.version(), hash, ConfirmationDecision.CONFIRMED,
                "late-confirm", Instant.now(), Instant.now().plusSeconds(30)
        ).isPresent());
        assertTrue(store.decideConfirmation(
                task.visitorId(), task.id(), 1, task.version(), hash, ConfirmationDecision.EXPIRED,
                "expiry-sweep", Instant.now(), null
        ).isPresent());
    }

    @Test
    void outboxClaimIsLeasedAndFailuresReachDeadLetter() {
        Conversation conversation = store.createConversation("visitor-outbox-" + UUID.randomUUID());
        AgentTask task = store.saveTask(new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "shop",
                "shop.order.commit", "idem-" + UUID.randomUUID(), Map.of("quantity", 1)
        ));
        Instant now = Instant.now();
        List<OutboxRecord> firstClaim = store.claimOutbox("worker-a", now, now.plusSeconds(30), 200);
        OutboxRecord taskRecord = firstClaim.stream()
                .filter(record -> record.aggregateId().equals(task.id()))
                .findFirst()
                .orElseThrow();

        assertTrue(store.claimOutbox("worker-b", now, now.plusSeconds(30), 200).stream()
                .noneMatch(record -> record.id().equals(taskRecord.id())));
        assertFalse(store.markOutboxPublished(taskRecord.id(), "worker-b", now));
        assertTrue(store.rescheduleOutbox(taskRecord.id(), "worker-a", now.plusSeconds(1), "BROKER_DOWN", 1));
        assertTrue(store.claimOutbox("worker-c", now.plusSeconds(60), now.plusSeconds(90), 200).stream()
                .noneMatch(record -> record.id().equals(taskRecord.id())));
    }

    @Test
    void restartRecoveryQueriesProviderAndNeverRepeatsTheWrite() {
        Conversation conversation = store.createConversation("visitor-recovery-" + UUID.randomUUID());
        Instant now = Instant.now();
        AgentTask dispatched = new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "hotel",
                "hotel.booking.commit", "idem-" + UUID.randomUUID(), Map.of("guestName", "masked"),
                TaskStatus.DISPATCHED, 1, 4, "hash", now.minusSeconds(1), null, null,
                null, now.minusSeconds(1), null, 0, now.minusSeconds(60), now.minusSeconds(30)
        );
        store.saveTask(dispatched);
        List<String> probes = new ArrayList<>();
        TaskRecoveryProbe probe = new TaskRecoveryProbe() {
            @Override
            public boolean supports(String actionCode) {
                return actionCode.equals("hotel.booking.commit");
            }

            @Override
            public TaskRecoveryResult query(AgentTask task) {
                probes.add(task.idempotencyKey());
                return TaskRecoveryResult.succeeded("provider-order-1", "订单已确认");
            }
        };
        TaskRecoveryService recovery = new TaskRecoveryService(store, List.of(probe));

        AgentTask recovered = recovery.recover(
                dispatched.visitorId(), dispatched.id(), "manual-refresh", now
        );

        assertEquals(TaskStatus.SUCCEEDED, recovered.status());
        assertEquals("provider-order-1", recovered.externalRef());
        assertEquals(List.of(dispatched.idempotencyKey()), probes);
    }

    @Test
    void unknownTaskCanAdvanceOnlyThroughTrustedResult() {
        Conversation conversation = store.createConversation("visitor-callback-" + UUID.randomUUID());
        Instant now = Instant.now();
        AgentTask unknown = new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "sports",
                "sports.ticket.commit", "idem-" + UUID.randomUUID(), Map.of("venue", "demo"),
                TaskStatus.UNKNOWN, 1, 2, "hash", now.minusSeconds(30), "external-1", null,
                "RESULT_UNKNOWN", null, now, 1, now.minusSeconds(60), now
        );
        store.saveTask(unknown);
        TrustedTaskResultService trustedResult = new TrustedTaskResultService(store);

        assertThrows(RuntimeException.class, () -> trustedResult.apply(
                unknown.visitorId(), unknown.id(), "callback-wrong", "external-2", TaskStatus.SUCCEEDED,
                "完成", null, now
        ));
        AgentTask succeeded = trustedResult.apply(
                unknown.visitorId(), unknown.id(), "callback-ok", "external-1", TaskStatus.SUCCEEDED,
                "完成", null, now
        );
        assertEquals(TaskStatus.SUCCEEDED, succeeded.status());
    }

    @Test
    void graphCheckpointSurvivesSaverRecreationAndKeepsMysqlStyleTruth() throws Exception {
        String threadId = "gth_" + UUID.randomUUID();
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Checkpoint checkpoint = Checkpoint.builder()
                .id("cp_" + UUID.randomUUID())
                .state(Map.of("step", "confirmed"))
                .nodeId("confirmation")
                .nextNodeId("execute")
                .build();
        checkpointSaver.put(config, checkpoint);

        BaseCheckpointSaver recreated = new DurableGraphCheckpointSaver(store, null);
        assertEquals(
                "confirmed",
                recreated.get(config).orElseThrow().getState().get("step")
        );
    }

    @Test
    void toolAndAuditDetailsRemainVisitorOwned() {
        Conversation conversation = store.createConversation("visitor-details-" + UUID.randomUUID());
        AgentTask task = store.saveTask(new AgentTask(
                "tsk_" + UUID.randomUUID(), conversation.visitorId(), conversation.id(), "tourism",
                "tourism.order.commit", "idem-" + UUID.randomUUID(), Map.of("attraction", "demo")
        ));
        Instant now = Instant.now();
        store.saveToolExecution(new ToolExecutionRecord(
                "tex_" + UUID.randomUUID(), task.id(), conversation.id(), task.actionCode(), "v1",
                ToolExecutionStatus.SUCCEEDED, Map.of("fields", List.of("attraction")),
                Map.of("status", "ok"), "external-demo", "trace-demo", now, now
        ));
        store.saveAudit(new AuditRecord(
                "aud_" + UUID.randomUUID(), task.visitorId(), task.id(), "request-demo",
                "ACTION_SUCCEEDED", "SYSTEM", Map.of(), now
        ));

        assertEquals(1, store.toolExecutions(task.visitorId(), task.id()).size());
        assertEquals(1, store.audits(task.visitorId(), task.id()).size());
        assertThrows(RuntimeException.class, () -> store.toolExecutions("another-visitor", task.id()));
        assertThrows(RuntimeException.class, () -> store.audits("another-visitor", task.id()));
    }
}
