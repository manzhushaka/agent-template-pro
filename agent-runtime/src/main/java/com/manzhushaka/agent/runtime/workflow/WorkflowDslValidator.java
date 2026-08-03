package com.manzhushaka.agent.runtime.workflow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static validation for workflow DSL schema 1.0. Checks structural integrity (single start/end,
 * reachability, no cycles, bounded size), edge conditions, and per-node config so that an invalid
 * graph can never be published or executed.
 */
public final class WorkflowDslValidator {
    private static final int MAX_NODES = 200;
    private static final int MAX_EDGES = 400;
    private static final int MAX_FIELDS = 20;
    private static final int MAX_PROMPT_CHARS = 8000;
    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "^(default|true|false|\\$[A-Za-z_][A-Za-z0-9_.]*"
                    + "(\\s*(==|!=|contains)\\s*('[^']*'|\\d+(\\.\\d+)?|true|false))?)$"
    );

    private WorkflowDslValidator() {
    }

    /** Validates and returns a compiled graph. Throws {@link WorkflowValidationException} on failure. */
    public static WorkflowCompiledGraph validate(WorkflowDsl dsl) {
        require(dsl != null, "Workflow DSL 不能为空。");
        require(WorkflowDsl.SUPPORTED_SCHEMA_VERSION.equals(dsl.schemaVersion()),
                "不支持的 DSL schema 版本: " + dsl.schemaVersion());
        require(notBlank(dsl.code()), "Workflow code 不能为空。");
        require(dsl.code().length() <= 120, "Workflow code 过长（最多 120 字符）。");
        require(notBlank(dsl.displayName()), "Workflow displayName 不能为空。");
        require(dsl.displayName().length() <= 160, "Workflow displayName 过长（最多 160 字符）。");
        require(dsl.nodes() != null && !dsl.nodes().isEmpty(), "Workflow 至少需要一个节点。");
        require(dsl.nodes().size() <= MAX_NODES, "Workflow 节点数超过上限 " + MAX_NODES + "。");
        require(dsl.edges() != null && !dsl.edges().isEmpty(), "Workflow 至少需要一条边。");
        require(dsl.edges().size() <= MAX_EDGES, "Workflow 边数超过上限 " + MAX_EDGES + "。");

        Map<String, WorkflowNode> nodesById = new LinkedHashMap<>();
        List<WorkflowNode> starts = new ArrayList<>();
        List<WorkflowNode> ends = new ArrayList<>();
        for (WorkflowNode node : dsl.nodes()) {
            require(node != null && notBlank(node.id()), "节点 id 不能为空。");
            require(node.id().length() <= 120, "节点 id 过长（最多 120 字符）。");
            require(!nodesById.containsKey(node.id()), "节点 id 重复: " + node.id());
            require(node.type() != null, "节点类型不能为空: " + node.id());
            nodesById.put(node.id(), node);
            if (node.type() == WorkflowNodeType.START) {
                starts.add(node);
            }
            if (node.type() == WorkflowNodeType.END) {
                ends.add(node);
            }
        }
        require(starts.size() == 1, "Workflow 必须且只能有一个 START 节点。");
        require(ends.size() >= 1, "Workflow 至少需要一个 END 节点。");
        String startId = starts.getFirst().id();

        Map<String, List<WorkflowEdge>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        Set<String> edgeKeys = new HashSet<>();
        for (WorkflowEdge edge : dsl.edges()) {
            require(edge != null, "Workflow 边不能为空。");
            require(nodesById.containsKey(edge.from()), "边起点不存在: " + edge.from());
            require(nodesById.containsKey(edge.to()), "边终点不存在: " + edge.to());
            require(edgeKeys.add(edge.key()), "重复边: " + edge.key());
            outgoing.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(edge.to(), key -> new ArrayList<>()).add(edge.from());
        }

        WorkflowNode start = nodesById.get(startId);
        require(incoming.getOrDefault(startId, List.of()).isEmpty(), "START 节点不能有入边。");
        require(outgoing.getOrDefault(startId, List.of()).size() == 1,
                "START 节点必须有且仅有一条出边。");
        for (WorkflowNode node : nodesById.values()) {
            if (node.type() == WorkflowNodeType.END) {
                require(outgoing.getOrDefault(node.id(), List.of()).isEmpty(), "END 节点不能有出边。");
            } else if (!node.id().equals(startId)) {
                require(!incoming.getOrDefault(node.id(), List.of()).isEmpty(),
                        "节点不可达（无入边）: " + node.id());
            }
        }

        // Reachability from START.
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!reachable.add(current)) {
                continue;
            }
            for (WorkflowEdge edge : outgoing.getOrDefault(current, List.of())) {
                queue.add(edge.to());
            }
        }
        for (WorkflowNode node : nodesById.values()) {
            require(reachable.contains(node.id()), "节点不可达（无法从 START 到达）: " + node.id());
        }

        // Cycle detection (DFS) - schema 1.0 rejects cycles; loop boundary is enforced by DAG-only.
        Set<String> visiting = new HashSet<>();
        Set<String> done = new HashSet<>();
        require(!hasCycle(startId, outgoing, visiting, done), "Workflow 不允许存在环（v1 仅支持 DAG）。");

        // Edge conditions.
        for (Map.Entry<String, List<WorkflowEdge>> entry : outgoing.entrySet()) {
            List<WorkflowEdge> edges = entry.getValue();
            WorkflowNode source = nodesById.get(entry.getKey());
            if (source.type() == WorkflowNodeType.PARALLEL) {
                require(edges.stream().allMatch(edge -> edge.condition() == null),
                        "PARALLEL 节点出边不允许带条件: " + source.id());
                require(edges.size() >= 2, "PARALLEL 节点至少需要两条出边: " + source.id());
                continue;
            }
            if (edges.size() == 1) {
                require(edges.getFirst().condition() == null,
                        "单出边节点不允许带条件: " + source.id());
                continue;
            }
            if (source.type() == WorkflowNodeType.CLASSIFIER) {
                long defaults = edges.stream().filter(edge -> "default".equals(edge.condition())).count();
                require(defaults == 1, "CLASSIFIER 节点必须且只能有一条 default 出边: " + source.id());
            } else {
                require(edges.stream().anyMatch(edge -> "default".equals(edge.condition())),
                        "多出边节点必须包含一条 default 出边: " + source.id());
            }
            for (WorkflowEdge edge : edges) {
                require(edge.condition() != null, "多出边节点每条出边都必须带条件: " + edge.key());
                require(CONDITION_PATTERN.matcher(edge.condition()).matches(),
                        "非法条件表达式: " + edge.key() + " => " + edge.condition());
            }
        }

        validateNodeConfigs(nodesById.values());
        Map<String, WorkflowEdge> edgesByKey = new HashMap<>();
        for (WorkflowEdge edge : dsl.edges()) {
            edgesByKey.put(edge.key(), edge);
        }
        return new WorkflowCompiledGraph(dsl, nodesById, outgoing, incoming, edgesByKey, startId);
    }

    private static void validateNodeConfigs(Iterable<WorkflowNode> nodes) {
        for (WorkflowNode node : nodes) {
            Map<String, Object> config = node.config();
            switch (node.type()) {
                case LLM -> {
                    require(notBlank(string(config.get("outputVar"))), "LLM 节点缺少 outputVar: " + node.id());
                    boolean inlinePrompt = notBlank(string(config.get("prompt")));
                    boolean boundPrompt = Boolean.TRUE.equals(config.get("useBoundPrompt"));
                    require(inlinePrompt || boundPrompt, "LLM 节点必须配置 prompt 或 useBoundPrompt=true: " + node.id());
                    if (inlinePrompt) {
                        require(string(config.get("prompt")).length() <= MAX_PROMPT_CHARS,
                                "LLM 节点 prompt 过长: " + node.id());
                    }
                }
                case ACTION -> {
                    require(notBlank(string(config.get("agentCode"))), "ACTION 节点缺少 agentCode: " + node.id());
                    require(notBlank(string(config.get("actionCode"))), "ACTION 节点缺少 actionCode: " + node.id());
                }
                case MCP_TOOL -> {
                    require(notBlank(string(config.get("toolVersionId"))), "MCP_TOOL 节点缺少 toolVersionId: " + node.id());
                    require(notBlank(string(config.get("outputVar"))), "MCP_TOOL 节点缺少 outputVar: " + node.id());
                }
                case RETRIEVAL -> {
                    require(notBlank(string(config.get("queryVar"))), "RETRIEVAL 节点缺少 queryVar: " + node.id());
                    require(notBlank(string(config.get("outputVar"))), "RETRIEVAL 节点缺少 outputVar: " + node.id());
                    Object topK = config.get("topK");
                    if (topK != null) {
                        int k = Integer.parseInt(String.valueOf(topK));
                        require(k >= 1 && k <= 20, "RETRIEVAL topK 必须在 1..20: " + node.id());
                    }
                }
                case INPUT -> {
                    Object fields = config.get("fields");
                    require(fields instanceof List<?> list && !list.isEmpty(), "INPUT 节点缺少 fields: " + node.id());
                    require(((List<?>) fields).size() <= MAX_FIELDS, "INPUT 节点字段超过 " + MAX_FIELDS + " 个: " + node.id());
                    for (Object field : (List<?>) fields) {
                        require(field instanceof Map<?, ?> map && notBlank(string(map.get("name"))),
                                "INPUT 节点字段必须包含非空 name: " + node.id());
                    }
                }
                case VARIABLE_ASSIGN -> {
                    Object assignments = config.get("assignments");
                    require(assignments instanceof Map<?, ?> map && !map.isEmpty(),
                            "VARIABLE_ASSIGN 节点缺少 assignments: " + node.id());
                }
                case START, END, CLASSIFIER, PARALLEL -> { }
            }
        }
    }

    private static boolean hasCycle(
            String nodeId,
            Map<String, List<WorkflowEdge>> outgoing,
            Set<String> visiting,
            Set<String> done
    ) {
        if (done.contains(nodeId)) {
            return false;
        }
        if (!visiting.add(nodeId)) {
            return true;
        }
        for (WorkflowEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
            if (hasCycle(edge.to(), outgoing, visiting, done)) {
                return true;
            }
        }
        visiting.remove(nodeId);
        done.add(nodeId);
        return false;
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new WorkflowValidationException(message);
        }
    }
}
