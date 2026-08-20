package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.ValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping("/experiments/{jobId}/report")
    public ExperimentReportDocument generate(@PathVariable String jobId) { return reports.generate(jobId); }

    @GetMapping("/experiments/{jobId}/report")
    public ExperimentReportDocument get(@PathVariable String jobId) { return reports.get(jobId); }

    @GetMapping("/experiments/{jobId}/report/data")
    public ExperimentReportData data(@PathVariable String jobId) { return reports.data(jobId); }

    @PostMapping("/experiments/{jobId}/report/validate")
    public ValidationResult validate(@PathVariable String jobId) { return reports.validate(jobId); }

    @GetMapping("/experiments/{jobId}/citations")
    public List<ArtifactCitation> citations(@PathVariable String jobId) { return reports.citations(jobId); }

    @GetMapping("/citations/{citationId}")
    public ArtifactCitation citation(@PathVariable String citationId) { return reports.citation(citationId); }
}
