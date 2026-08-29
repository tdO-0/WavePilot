package org.example.wavepilot.knowledge.evaluation;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/retrieval-evaluations")
public class RetrievalEvaluationController {
    private final RetrievalEvaluationService service;

    public RetrievalEvaluationController(RetrievalEvaluationService service) { this.service = service; }

    @PostMapping("/run")
    public RetrievalEvaluationReport run() { return service.run(); }

    @GetMapping("/{evaluationId}")
    public RetrievalEvaluationReport get(@PathVariable String evaluationId) { return service.get(evaluationId); }

    @GetMapping(value = "/{evaluationId}/report.md", produces = MediaType.TEXT_MARKDOWN_VALUE)
    public String markdown(@PathVariable String evaluationId) { return service.markdown(evaluationId); }
}
