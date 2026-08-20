package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ReportNumericGroundingTest {
    @TempDir Path root;

    @Test void rejectsConclusionValueAbsentFromSourceCitation() throws Exception {
        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        ExperimentReportData data = fixture.data();
        List<ReportConclusion> conclusions = new ArrayList<>(data.conclusions());
        ReportConclusion original = conclusions.get(0);
        conclusions.set(0, new ReportConclusion(original.conclusionId(), "伪造准确率 0.777",
                original.metricName(), 0.777, original.citationIds(), CitationStatus.VERIFIED));
        assertFalse(fixture.validator().validate(data.jobId(),
                ReportTestSupport.withConclusions(data, conclusions)).valid());
    }
}
