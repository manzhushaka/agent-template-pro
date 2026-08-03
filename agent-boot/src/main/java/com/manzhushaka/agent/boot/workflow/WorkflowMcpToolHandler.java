package com.manzhushaka.agent.boot.workflow;

import com.manzhushaka.agent.controlplane.McpControlPlaneService;
import com.manzhushaka.agent.controlplane.McpRuntimeToolSnapshot;
import com.manzhushaka.agent.controlplane.McpTransportCallResult;
import com.manzhushaka.agent.controlplane.McpTransportClient;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.trace.TraceRecorder;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeContext;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeHandler;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeResult;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeType;
import com.manzhushaka.agent.runtime.workflow.WorkflowTaskPreparation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP_TOOL node. Read tools call the controlled transport directly; write tools first create a
 * WAITING_CONFIRMATION task through the workflow gate and the transport call happens only after a
 * confirmed decision. A transport failure after the request may have reached the server marks the
 * node RESULT_UNKNOWN and blocks automatic retry.
 */
@Component
public class WorkflowMcpToolHandler implements WorkflowNodeHandler {
    private final McpControlPlaneService mcpService;
    private final McpTransportClient transport;
    private final TraceRecorder traceRecorder;
    private final Duration timeout;

    public WorkflowMcpToolHandler(
            McpControlPlaneService mcpService,
            McpTransportClient transport,
            TraceRecorder traceRecorder,
            @Value("${agent.console.mcp-timeout:PT5S}") Duration timeout
    ) {
        this.mcpService = mcpService;
        this.transport = transport;
        this.traceRecorder = traceRecorder;
        this.timeout = timeout;
    }

    @Override
    public boolean supports(WorkflowNodeType type) {
        return type == WorkflowNodeType.MCP_TOOL;
    }

    @Override
    public WorkflowNodeResult execute(WorkflowNodeContext context) {
        String toolVersionId = string(context.node().config().get("toolVersionId"));
        String outputVar = string(context.node().config().get("outputVar"));
        Map<String, Object> input = resolveInput(context);
        McpRuntimeToolSnapshot snapshot = mcpService.runtimeTool(toolVersionId);
        WorkflowNodeRun nodeRun = context.runStore().findNodeRun(context.run().id(), context.node().id())
                .orElse(null);
        if (snapshot.writeTool()) {
            if (nodeRun == null || nodeRun.confirmationTaskId() == null) {
                WorkflowTaskPreparation preparation = context.confirmationGate().prepare(
                        context.visitorRef(), context.run().id(), context.node().id(),
                        context.requestId(), "mcp." + snapshot.toolName(), input
                );
                return WorkflowNodeResult.waitingConfirmation(
                        context.variables(), preparation.taskId(),
                        preparation.confirmationVersion(), preparation.confirmationSnapshotHash(),
                        List.of()
                );
            }
            AgentTask task = context.confirmationGate().confirmedTask(
                    context.visitorRef(), nodeRun.confirmationTaskId());
            McpTransportCallResult result = call(context, snapshot, input);
            if (McpTransportCallResult.OK.equals(result.status())) {
                context.confirmationGate().completeConfirmed(
                        context.visitorRef(), task.id(), task.version(), context.requestId());
                Map<String, Object> variables = new LinkedHashMap<>(context.variables());
                variables.put(outputVar, result.output());
                return WorkflowNodeResult.succeeded(Map.of(outputVar, result.output()), variables, List.of());
            }
            context.confirmationGate().failConfirmed(
                    context.visitorRef(), task.id(), task.version(), result.errorCode());
            return WorkflowNodeResult.failed(
                    context.variables(),
                    McpTransportCallResult.RESULT_UNKNOWN.equals(result.status()) ? "RESULT_UNKNOWN" : result.errorCode()
            );
        }
        McpTransportCallResult result = call(context, snapshot, input);
        if (McpTransportCallResult.OK.equals(result.status())) {
            Map<String, Object> variables = new LinkedHashMap<>(context.variables());
            variables.put(outputVar, result.output());
            return WorkflowNodeResult.succeeded(Map.of(outputVar, result.output()), variables, List.of());
        }
        return WorkflowNodeResult.failed(
                context.variables(),
                McpTransportCallResult.RESULT_UNKNOWN.equals(result.status()) ? "RESULT_UNKNOWN" : result.errorCode()
        );
    }

    private McpTransportCallResult call(
            WorkflowNodeContext context,
            McpRuntimeToolSnapshot snapshot,
            Map<String, Object> input
    ) {
        Instant startedAt = Instant.now();
        McpTransportCallResult result = transport.call(
                snapshot.serverConnection(), snapshot.toolName(), input, timeout
        );
        SpanStatus status = McpTransportCallResult.OK.equals(result.status()) ? SpanStatus.OK : SpanStatus.ERROR;
        traceRecorder.recordTool(
                context.requestId(), context.visitorRef(), null, null, context.requestId(),
                snapshot.toolName(), status, result.errorCode(), startedAt, Instant.now()
        );
        return result;
    }

    private static Map<String, Object> resolveInput(WorkflowNodeContext context) {
        Object inputValue = context.node().config().get("input");
        if (inputValue instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        String inputVar = string(context.node().config().get("inputVar"));
        if (!inputVar.isBlank() && context.variables().get(inputVar) instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
