package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ObservabilityOverviewResponse;
import com.manzhushaka.agent.consoleapi.dto.PageResponse;
import com.manzhushaka.agent.consoleapi.dto.TraceSpanResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.service.ObservabilityService;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/console/v1/observability")
public class ObservabilityController {
    private final ConsoleAuthenticationService authenticationService;
    private final ObservabilityService observabilityService;

    public ObservabilityController(
            ConsoleAuthenticationService authenticationService,
            ObservabilityService observabilityService
    ) {
        this.authenticationService = authenticationService;
        this.observabilityService = observabilityService;
    }

    @GetMapping("/overview")
    public ObservabilityOverviewResponse overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.requirePermission(authorization, ControlPlaneService.RUNTIME_READ);
        return observabilityService.overview();
    }

    @GetMapping("/traces")
    public PageResponse<TraceSpanResponse> traces(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "query", required = false) String query
    ) {
        authenticationService.requirePermission(authorization, "trace:read");
        return observabilityService.traces(page, size, type, status, query);
    }

    @GetMapping("/traces/{traceId}")
    public Map<String, Object> trace(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String traceId
    ) {
        authenticationService.requirePermission(authorization, "trace:read");
        return observabilityService.trace(traceId);
    }
}
