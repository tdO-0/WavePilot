package org.example.wavepilot.modelrouting;

public interface ModelRouter {
    ModelRoutingDecision route(ModelTaskType taskType, boolean semanticJudgmentRequired);
}
