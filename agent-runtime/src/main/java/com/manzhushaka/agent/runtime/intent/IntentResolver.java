package com.manzhushaka.agent.runtime.intent;

import com.manzhushaka.agent.spi.action.ActionDescriptor;

import java.util.List;

/** Converts a visitor message into a candidate action and extracted parameters. */
public interface IntentResolver {
    IntentDecision resolve(String content, List<ActionDescriptor> descriptors);
}
