package com.manzhushaka.agent.runtime.workflow;

import java.util.List;

/** Paged workflow run view used by the Console. */
public record WorkflowRunPage(List<WorkflowRun> items, long total) {
    public WorkflowRunPage {
        items = List.copyOf(items);
    }
}
