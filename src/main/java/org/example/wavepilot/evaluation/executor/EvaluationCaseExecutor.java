package org.example.wavepilot.evaluation.executor;

import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationModel;

/** Executes one evaluation case and produces the stored per-case result. */
public interface EvaluationCaseExecutor {

    EvaluationCaseResult execute(EvaluationCase evaluationCase, EvaluationModel model);
}
