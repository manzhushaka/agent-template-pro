package com.manzhushaka.agent.controlplane.workflow;

import java.util.List;
import java.util.Optional;

/** Durable boundary for workflow definitions and versions. */
public interface WorkflowRepository {
    Optional<WorkflowDefinition> workflow(String id);

    Optional<WorkflowDefinition> workflowByCode(String code);

    WorkflowDefinition saveWorkflow(WorkflowDefinition workflow);

    List<WorkflowDefinition> workflows(String keyword, int page, int size);

    long countWorkflows(String keyword);

    Optional<WorkflowVersion> version(String versionId);

    List<WorkflowVersion> versions(String workflowId);

    WorkflowVersion saveVersion(WorkflowVersion version);

    int nextVersionNo(String workflowId);
}
