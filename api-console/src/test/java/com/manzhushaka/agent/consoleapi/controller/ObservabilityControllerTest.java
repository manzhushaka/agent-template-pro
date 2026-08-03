package com.manzhushaka.agent.consoleapi.controller;

import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationService;
import com.manzhushaka.agent.consoleapi.security.ConsoleAuthenticationException;
import com.manzhushaka.agent.consoleapi.service.ObservabilityService;
import com.manzhushaka.agent.controlplane.ControlPlaneAccessDeniedException;
import com.manzhushaka.agent.controlplane.ControlPlanePrincipal;
import com.manzhushaka.agent.controlplane.ControlPlaneService;
import com.manzhushaka.agent.runtime.store.SpanPage;
import com.manzhushaka.agent.runtime.store.SpanQuery;
import com.manzhushaka.agent.runtime.store.SpanRecord;
import com.manzhushaka.agent.runtime.store.SpanStatus;
import com.manzhushaka.agent.runtime.store.SpanType;
import com.manzhushaka.agent.runtime.store.TraceStore;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.trace.LocalTraceQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObservabilityControllerTest {
    @Test
    void servesOverviewTracesAndTraceDetailWithTracePermission() {
        ControlPlaneService controlPlaneService = new ControlPlaneService();
        ControlPlanePrincipal admin = controlPlaneService.principal("admin", "ADMIN");
        ConsoleAuthenticationService authentication = authenticated(admin);

        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        Instant now = Instant.now();
        SpanRecord request = span("s-req-1", "trc-1", SpanType.REQUEST, SpanStatus.OK, now);
        SpanRecord model = span("s-model-1", "trc-1", SpanType.MODEL, SpanStatus.OK, now);
        traceStore.saveSpan(request);
        traceStore.saveSpan(model);
        LocalTraceQueryService query = new LocalTraceQueryService(provider(traceStore));

        RuntimeStore runtimeStore = mock(RuntimeStore.class);
        when(runtimeStore.listTasks()).thenReturn(List.of(new AgentTask(
                "tk-1", "visitor-1", "cv-1", "hotel.room.search", "idem-1", Map.of()
        )));
        ObservabilityService service = new ObservabilityService(query, provider(traceStore), runtimeStore);
        WebTestClient client = WebTestClient.bindToController(
                        new ObservabilityController(authentication, service))
                .controllerAdvice(new ConsoleApiExceptionAdvice())
                .build();

        client.get().uri("/api/console/v1/observability/overview")
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalSpans").isEqualTo(2)
                .jsonPath("$.model.calls").isEqualTo(1);

        client.get().uri(uriBuilder -> uriBuilder.path("/api/console/v1/observability/traces")
                        .queryParam("page", 1).queryParam("size", 10)
                        .queryParam("type", "MODEL").build())
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].traceId").isEqualTo("trc-1");

        client.get().uri("/api/console/v1/observability/traces/trc-1")
                .header("Authorization", "Bearer admin")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.traceId").isEqualTo("trc-1")
                .jsonPath("$.spanCount").isEqualTo(2)
                .jsonPath("$.conversationIds[0]").isEqualTo("cv-1");
    }

    @Test
    void mapsMissingTracePermissionToStableForbiddenResponse() {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePermission(any(), anyString()))
                .thenThrow(new ControlPlaneAccessDeniedException());
        ObservabilityService service = mock(ObservabilityService.class);
        WebTestClient client = WebTestClient.bindToController(
                        new ObservabilityController(authentication, service))
                .controllerAdvice(new ConsoleApiExceptionAdvice())
                .build();

        client.get().uri("/api/console/v1/observability/traces")
                .header("Authorization", "Bearer reader")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_PERMISSION_DENIED");
    }

    @Test
    void mapsExpiredSessionToStableUnauthorizedResponse() {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePermission(any(), anyString())).thenThrow(
                new ConsoleAuthenticationException(ConsoleAuthenticationException.Reason.SESSION_INVALID));
        ObservabilityService service = mock(ObservabilityService.class);

        WebTestClient.bindToController(new ObservabilityController(authentication, service))
                .controllerAdvice(new ConsoleApiExceptionAdvice())
                .build()
                .get().uri("/api/console/v1/observability/overview")
                .header("Authorization", "Bearer expired")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody()
                .jsonPath("$.code").isEqualTo("CONSOLE_SESSION_INVALID");
    }

    private static SpanRecord span(String id, String traceId, SpanType type, SpanStatus status, Instant now) {
        return new SpanRecord(
                id, traceId, null, type, type == SpanType.REQUEST ? "chat.request" : "model.call",
                status, "visitor-1", "cv-1", null, "req-1", "hotel", null, null,
                "openai", "gpt-4o", 10, 20, 30, 42L, null,
                Map.of(), Map.of("model", "gpt-4o"), now, now.plusMillis(42), now
        );
    }

    private static ConsoleAuthenticationService authenticated(ControlPlanePrincipal principal) {
        ConsoleAuthenticationService authentication = mock(ConsoleAuthenticationService.class);
        when(authentication.requirePermission(any(), anyString())).thenReturn(principal);
        return authentication;
    }

    private static org.springframework.beans.factory.ObjectProvider<TraceStore> provider(TraceStore store) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("traceStore", store);
        return beanFactory.getBeanProvider(TraceStore.class);
    }

    /** Minimal in-memory TraceStore double; api-console must not depend on agent-infrastructure. */
    private static final class InMemoryTraceStore implements TraceStore {
        private final List<SpanRecord> spans = new CopyOnWriteArrayList<>();

        @Override
        public void saveSpan(SpanRecord span) {
            spans.add(span);
        }

        @Override
        public SpanPage querySpans(SpanQuery query) {
            List<SpanRecord> filtered = new ArrayList<>(spans.stream()
                    .filter(span -> query.type() == null || span.type() == query.type())
                    .sorted(Comparator.comparing(SpanRecord::startedAt).reversed())
                    .toList());
            int from = (query.page() - 1) * query.size();
            if (from >= filtered.size()) {
                return new SpanPage(List.of(), filtered.size());
            }
            return new SpanPage(filtered.subList(from, Math.min(filtered.size(), from + query.size())),
                    filtered.size());
        }

        @Override
        public List<SpanRecord> spansByTrace(String traceId) {
            return spans.stream().filter(span -> span.traceId().equals(traceId)).toList();
        }

        @Override
        public Optional<SpanRecord> findSpan(String spanId) {
            return spans.stream().filter(span -> span.id().equals(spanId)).findFirst();
        }

        @Override
        public List<SpanRecord> recentSpans(SpanType type, int limit) {
            return spans.stream()
                    .filter(span -> type == null || span.type() == type)
                    .limit(limit)
                    .toList();
        }
    }
}
