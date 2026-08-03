package com.manzhushaka.agent.runtime.model;

import com.manzhushaka.agent.spi.action.ActionMode;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Defines which domain actions may be exposed as model-callable tools. */
public final class ModelToolExposurePolicy {
    private static final Set<ActionMode> MODEL_CALLABLE_MODES = EnumSet.of(ActionMode.QUERY, ActionMode.DRAFT);

    public boolean isModelCallable(ActionMode mode) {
        return MODEL_CALLABLE_MODES.contains(Objects.requireNonNull(mode, "mode cannot be null"));
    }

    public void requireModelCallable(ActionMode mode) {
        if (!isModelCallable(mode)) {
            throw new IllegalArgumentException("High-risk actions must execute through the runtime confirmation gate");
        }
    }
}
