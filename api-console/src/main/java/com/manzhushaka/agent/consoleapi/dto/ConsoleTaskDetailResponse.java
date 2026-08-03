package com.manzhushaka.agent.consoleapi.dto;

import java.util.List;

public record ConsoleTaskDetailResponse(
        ConsoleTaskResponse task,
        List<ConsoleToolExecutionResponse> toolExecutions,
        List<ConsoleAuditResponse> audits
) {
    public ConsoleTaskDetailResponse {
        toolExecutions = List.copyOf(toolExecutions);
        audits = List.copyOf(audits);
    }
}
