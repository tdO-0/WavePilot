package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryEvaluationRepository implements EvaluationRepository {

    private final ConcurrentMap<String, EvaluationRun> runs = new ConcurrentHashMap<>();

    @Override
    public EvaluationRun save(EvaluationRun run) {
        runs.put(run.evaluationId(), run);
        return run;
    }

    @Override
    public Optional<EvaluationRun> findById(String evaluationId) {
        return Optional.ofNullable(runs.get(evaluationId));
    }

    @Override
    public List<EvaluationRun> findAll() {
        return runs.values().stream()
                .sorted(Comparator.comparing(EvaluationRun::startedAt).reversed())
                .toList();
    }
}
