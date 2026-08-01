package com.manzhushaka.agent.runtime.store;

public record RouteMetrics(long routeTotal, long ambiguousTotal, long failureTotal, long switchTotal) {
}
