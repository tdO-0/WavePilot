package org.example.wavepilot.evaluation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EvaluationController {

    private final EvaluationService evaluationService;

    public EvaluationController(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    @PostMapping("/evaluations/run")
    public EvaluationRun run(@RequestBody(required = false) EvaluationRunRequest request) {
        return evaluationService.run(request == null ? null : request.datasetName(),
                request == null ? null : request.modelName());
    }

    @GetMapping("/evaluations")
    public List<EvaluationRun> list() { return evaluationService.list(); }

    @GetMapping("/evaluations/{evaluationId}")
    public EvaluationRun get(@PathVariable String evaluationId) {
        return evaluationService.get(evaluationId);
    }

    @GetMapping("/evaluations/{evaluationId}/report")
    public EvaluationReport report(@PathVariable String evaluationId) {
        return evaluationService.report(evaluationId);
    }

    @PostMapping("/evaluations/compare")
    public EvaluationComparison compare(@RequestBody EvaluationCompareRequest request) {
        return evaluationService.compare(request.baselineEvaluationId(), request.candidateEvaluationId());
    }

    public record EvaluationRunRequest(String datasetName, String modelName) { }

    public record EvaluationCompareRequest(String baselineEvaluationId, String candidateEvaluationId) { }
}
