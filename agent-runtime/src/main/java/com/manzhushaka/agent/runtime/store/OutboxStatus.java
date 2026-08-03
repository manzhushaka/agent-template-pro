package com.manzhushaka.agent.runtime.store;

public enum OutboxStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    DEAD
}
