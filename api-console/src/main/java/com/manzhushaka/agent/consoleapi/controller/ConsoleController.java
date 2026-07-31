package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.runtime.chat.ChatOrchestrator;
import com.manzhushaka.agent.runtime.task.AgentTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/console/v1")
public class ConsoleController {
    private final ChatOrchestrator orchestrator; private final String accessKey;
    public ConsoleController(ChatOrchestrator orchestrator, @Value("${agent.console.access-key}") String accessKey) { this.orchestrator=orchestrator; this.accessKey=accessKey; }
    @PostMapping("/auth/login") public Map<String,Object> login(@RequestBody Map<String,String> request) { check(request.get("accessKey")); return Map.of("authenticated", true, "role", "local-admin"); }
    @GetMapping("/overview") public Map<String,Object> overview(@RequestHeader("X-Console-Key") String key) { check(key); List<AgentTask> tasks=orchestrator.tasks(); long active=tasks.stream().filter(t -> t.status().name().startsWith("WAITING")).count(); return Map.of("health", "UP", "taskTotal", tasks.size(), "activeTasks", active, "mode", "in-memory-demo"); }
    @GetMapping("/tasks") public List<AgentTask> tasks(@RequestHeader("X-Console-Key") String key) { check(key); return orchestrator.tasks(); }
    @GetMapping("/runtime-config") public Map<String,Object> config(@RequestHeader("X-Console-Key") String key) { check(key); return Map.of("visitorIdentity", "HMAC cookie", "storage", "in-memory-demo", "model", "deterministic demo router", "secretsConfigured", !accessKey.equals("local-console-key")); }
    private void check(String key) { if (!Objects.equals(accessKey, key)) throw new ConsoleForbidden(); }
    @ResponseStatus(HttpStatus.UNAUTHORIZED) private static class ConsoleForbidden extends RuntimeException { }
}
