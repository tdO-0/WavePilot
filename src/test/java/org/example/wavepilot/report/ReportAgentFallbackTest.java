package org.example.wavepilot.report;

import org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAgentFallbackTest {
    @TempDir Path root;

    @Test void fallsBackToTemplateWhenModelFailsWithoutExternalCall() throws Exception {
        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        InMemoryExperimentJobRepository jobs = new InMemoryExperimentJobRepository();
        jobs.save(fixture.job());
        ReportLanguageModel failing = (data, markdown) -> { throw new IllegalStateException("quota unavailable"); };
        ReportService service = new ReportService(jobs, fixture.registry(), fixture.assembler(),
                fixture.validator(), new TemplateExperimentReportGenerator(), new ControlledReportAgent(),
                Optional.of(failing));
        ExperimentReportDocument report = service.generate(fixture.job().getJobId());
        assertEquals("TEMPLATE_FALLBACK", report.generatedBy());
        assertTrue(report.markdown().contains("SIMPLIFIED_BASELINE"));
        assertEquals(CitationStatus.VERIFIED, report.status());
    }
}
