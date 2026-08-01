package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.dto.ConsoleCaptchaResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleErrorResponse;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginRequest;
import com.manzhushaka.agent.consoleapi.dto.ConsoleLoginResponse;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.task.AgentTask;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/console/v1")
public class ConsoleController {
    private final ChatOrchestrator orchestrator;
    private final ConsoleAuthenticationService authenticationService;
    private final Environment environment;

    public ConsoleController(
            ChatOrchestrator orchestrator,
            ConsoleAuthenticationService authenticationService,
            Environment environment
    ) {
        this.orchestrator = orchestrator;
        this.authenticationService = authenticationService;
        this.environment = environment;
    }

    @GetMapping("/auth/captcha")
    public ResponseEntity<ConsoleCaptchaResponse> captcha() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(authenticationService.createCaptcha());
    }

    @PostMapping("/auth/login")
    public ResponseEntity<ConsoleLoginResponse> login(@Valid @RequestBody ConsoleLoginRequest request) {
        ConsoleLoginResponse response = authenticationService.login(
                request.username(),
                request.password(),
                request.captchaId(),
                request.captchaCode()
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.requireSession(authorization);
        List<AgentTask> tasks = orchestrator.tasks();
        long active = tasks.stream()
                .filter(task -> task.status().name().startsWith("WAITING"))
                .count();
        return Map.of(
                "health", "UP",
                "taskTotal", tasks.size(),
                "activeTasks", active,
                "agentTotal", orchestrator.registeredAgents().size(),
                "mode", "in-memory-demo"
        );
    }

    @GetMapping("/agents")
    public List<Map<String, Object>> agents(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.requireSession(authorization);
        return orchestrator.registeredAgents().stream().map(agent -> {
            var metrics = orchestrator.routeMetrics(agent.descriptor().code());
            Map<String, Long> modes = agent.actions().values().stream().collect(java.util.stream.Collectors.groupingBy(
                    action -> action.descriptor().mode().name(),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()
            ));
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("code", agent.descriptor().code());
            view.put("displayName", agent.descriptor().displayName());
            view.put("enabled", true);
            view.put("visibleToVisitor", agent.descriptor().visibleToVisitor());
            view.put("iconKey", agent.descriptor().iconKey());
            view.put("actionCount", agent.actions().size());
            view.put("actionModes", modes);
            view.put("routerStatus", "READY");
            view.put("routeTotal", metrics.routeTotal());
            view.put("ambiguousTotal", metrics.ambiguousTotal());
            view.put("failureTotal", metrics.failureTotal());
            view.put("switchTotal", metrics.switchTotal());
            return Map.copyOf(view);
        }).toList();
    }

    @GetMapping("/tasks")
    public List<AgentTask> tasks(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.requireSession(authorization);
        return orchestrator.tasks();
    }

    @GetMapping("/runtime-config")
    public Map<String, Object> config(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        authenticationService.requireSession(authorization);
        return Map.of(
                "visitorIdentity", "HMAC cookie",
                "storage", environment.acceptsProfiles(Profiles.of("runtime-jdbc"))
                        ? "mysql-jdbc"
                        : "in-memory-demo",
                "model", configuredModel(),
                "routingMode", orchestrator.routingMode(),
                "modelCredentialConfigured", modelCredentialConfigured(),
                "secretsConfigured", !authenticationService.usesDemoCredentials()
        );
    }

    private String configuredModel() {
        if (environment.acceptsProfiles(Profiles.of("model-minimax"))) {
            return "MiniMax Anthropic / "
                    + environment.getProperty("agent.model.minimax.model", "minimax-m.2.7-highspeed");
        }
        if (environment.acceptsProfiles(Profiles.of("model-openai"))) {
            return "OpenAI-compatible / "
                    + environment.getProperty("spring.ai.openai.chat.options.model", "configured model");
        }
        return "deterministic demo router";
    }

    private boolean modelCredentialConfigured() {
        if (environment.acceptsProfiles(Profiles.of("model-minimax"))) {
            return hasText(environment.getProperty("agent.model.minimax.api-key"));
        }
        if (environment.acceptsProfiles(Profiles.of("model-openai"))) {
            return hasText(environment.getProperty("spring.ai.openai.api-key"));
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @ExceptionHandler(ConsoleAuthenticationException.class)
    public ResponseEntity<ConsoleErrorResponse> handleAuthentication(ConsoleAuthenticationException exception) {
        HttpStatus status = exception.reason()
                == ConsoleAuthenticationException.Reason.INVALID_CAPTCHA
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse(exception.reason().code(), exception.reason().message()));
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ConsoleErrorResponse> handleInvalidRequest() {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ConsoleErrorResponse("CONSOLE_LOGIN_INVALID", "请完整填写用户名、密码和图片验证码。"));
    }
}
