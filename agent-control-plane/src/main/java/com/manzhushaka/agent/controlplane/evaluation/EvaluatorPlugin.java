package com.manzhushaka.agent.controlplane.evaluation;

/** Deterministic or controlled-model evaluation plugin; config drives behaviour. */
public interface EvaluatorPlugin {
    String type();

    EvaluatorResult evaluate(EvaluatorContext context);
}
