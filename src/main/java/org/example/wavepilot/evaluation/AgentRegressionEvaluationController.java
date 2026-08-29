package org.example.wavepilot.evaluation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent-regression-evaluations")
public class AgentRegressionEvaluationController {
    private final AgentRegressionEvaluationService service;

    public AgentRegressionEvaluationController(AgentRegressionEvaluationService service) { this.service = service; }

    @PostMapping("/run")
    public AgentRegressionEvaluationReport run(@RequestBody RunRequest request) {
        return service.evaluate(request.runId(), request.retrievalEvaluationId(), request.replayId(),
                request.profile() == null ? AgentEvaluationProfile.CANDIDATE : request.profile());
    }

    @GetMapping("/{evaluationId}")
    public AgentRegressionEvaluationReport get(@PathVariable String evaluationId) {
        return service.get(evaluationId);
    }

    @PostMapping("/compare")
    public AgentRegressionComparison compare(@RequestBody CompareRequest request) {
        return service.compare(request.baselineEvaluationId(), request.candidateEvaluationId());
    }

    public record RunRequest(String runId, String retrievalEvaluationId, String replayId,
                             AgentEvaluationProfile profile) { }
    public record CompareRequest(String baselineEvaluationId, String candidateEvaluationId) { }
}
