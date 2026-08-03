package com.manzhushaka.agent.runtime.store;

/** Terminal span status; sensitive payloads are never part of a span. */
public enum SpanStatus {
    OK,
    ERROR,
    TIMEOUT,
    UNKNOWN,
    SKIPPED
}
