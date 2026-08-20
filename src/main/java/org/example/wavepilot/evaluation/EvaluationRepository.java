package org.example.wavepilot.evaluation;

import java.util.List;
import java.util.Optional;

public interface EvaluationRepository {
    EvaluationRun save(EvaluationRun run);
    Optional<EvaluationRun> findById(String evaluationId);
    List<EvaluationRun> findAll();
}
