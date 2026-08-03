package com.manzhushaka.agent.boot;

import com.manzhushaka.agent.controlplane.KnowledgeBaseService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeIndexScheduler {
    private final KnowledgeBaseService service;

    public KnowledgeIndexScheduler(KnowledgeBaseService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${agent.knowledge.index-fixed-delay:PT30S}")
    public void index() {
        service.processQueuedJobs("boot-indexer", 10);
        service.compensateDeletedDocuments("boot-indexer", 20);
    }
}
