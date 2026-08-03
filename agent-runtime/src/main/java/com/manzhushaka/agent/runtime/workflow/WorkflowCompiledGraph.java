package com.manzhushaka.agent.runtime.workflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validated graph structure used by the execution engine and debug preview. */
public record WorkflowCompiledGraph(
        WorkflowDsl dsl,
        Map<String, WorkflowNode> nodes,
        Map<String, List<WorkflowEdge>> outgoing,
        Map<String, List<String>> incoming,
        Map<String, WorkflowEdge> edgesByKey,
        String startNodeId
) {
    public WorkflowCompiledGraph {
        nodes = Map.copyOf(nodes);
        outgoing = Map.copyOf(outgoing);
        incoming = Map.copyOf(incoming);
        edgesByKey = Map.copyOf(edgesByKey);
    }

    public WorkflowEdge edge(String key) {
        WorkflowEdge edge = edgesByKey.get(key);
        if (edge == null) {
            throw new WorkflowValidationException("图缺少边: " + key);
        }
        return edge;
    }

    public WorkflowNode node(String id) {
        WorkflowNode node = nodes.get(id);
        if (node == null) {
            throw new WorkflowValidationException("图缺少节点: " + id);
        }
        return node;
    }

    public List<WorkflowEdge> edgesFrom(String nodeId) {
        return List.copyOf(outgoing.getOrDefault(nodeId, List.of()));
    }

    /** Ordered snapshot used by the editor preview; keyed by node id with display metadata. */
    public Map<String, Object> preview() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("schemaVersion", dsl.schemaVersion());
        view.put("startNodeId", startNodeId);
        view.put("nodeCount", nodes.size());
        view.put("edgeCount", outgoing.values().stream().mapToInt(List::size).sum());
        view.put("nodes", nodes.values());
        view.put("edges", outgoing.values().stream().flatMap(List::stream).toList());
        return view;
    }
}
