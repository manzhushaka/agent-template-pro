package com.manzhushaka.agent.controlplane.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory workflow repository for the default demo profile. */
@Repository
@Profile("!runtime-jdbc")
public class InMemoryWorkflowRepository implements WorkflowRepository {
    private final Map<String, WorkflowDefinition> workflows = new ConcurrentHashMap<>();
    private final Map<String, WorkflowVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> versionCounters = new ConcurrentHashMap<>();

    @Override
    public Optional<WorkflowDefinition> workflow(String id) {
        return Optional.ofNullable(workflows.get(id));
    }

    @Override
    public Optional<WorkflowDefinition> workflowByCode(String code) {
        return workflows.values().stream().filter(workflow -> workflow.code().equals(code)).findFirst();
    }

    @Override
    public WorkflowDefinition saveWorkflow(WorkflowDefinition workflow) {
        workflows.put(workflow.id(), workflow);
        return workflow;
    }

    @Override
    public List<WorkflowDefinition> workflows(String keyword, int page, int size) {
        List<WorkflowDefinition> all = workflows.values().stream()
                .filter(workflow -> keyword == null || keyword.isBlank()
                        || workflow.code().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || workflow.displayName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(WorkflowDefinition::createdAt).reversed())
                .toList();
        int from = Math.max(0, (page - 1) * size);
        if (from >= all.size()) {
            return List.of();
        }
        return all.subList(from, Math.min(all.size(), from + size));
    }

    @Override
    public long countWorkflows(String keyword) {
        return workflows.values().stream()
                .filter(workflow -> keyword == null || keyword.isBlank()
                        || workflow.code().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || workflow.displayName().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                .count();
    }

    @Override
    public Optional<WorkflowVersion> version(String versionId) {
        return Optional.ofNullable(versions.get(versionId));
    }

    @Override
    public List<WorkflowVersion> versions(String workflowId) {
        List<WorkflowVersion> all = new ArrayList<>();
        for (WorkflowVersion version : versions.values()) {
            if (version.workflowId().equals(workflowId)) {
                all.add(version);
            }
        }
        all.sort(Comparator.comparingInt(WorkflowVersion::versionNo));
        return all;
    }

    @Override
    public WorkflowVersion saveVersion(WorkflowVersion version) {
        versions.put(version.id(), version);
        versionCounters.computeIfAbsent(version.workflowId(), key -> new AtomicInteger())
                .accumulateAndGet(version.versionNo(), Math::max);
        return version;
    }

    @Override
    public int nextVersionNo(String workflowId) {
        return versionCounters.computeIfAbsent(workflowId, key -> new AtomicInteger()).incrementAndGet();
    }
}
