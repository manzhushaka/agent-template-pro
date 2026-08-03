package com.manzhushaka.agent.controlplane.evaluation;

/**
 * Executes one evaluation case through the shared Runtime chain (never a bypass). The
 * production implementation lives in the boot module where ChatOrchestrator is visible;
 * tests may substitute a stub.
 */
public interface AgentEvaluationExecutor {
    EvaluationRunOutcome execute(EvaluationExecutionContext execution);
}
