package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateExperimentReportGeneratorTest {
    @TempDir Path root;

    @Test void describesLocalMatlabExecutionTruthfully() throws Exception {
        ExperimentReportData data = ReportTestSupport.fixture(root).data();
        ExperimentReportDocument report = new TemplateExperimentReportGenerator().generate(data);
        for (int section = 1; section <= 10; section++) assertTrue(report.markdown().contains("## " + section + "."));
        assertTrue(report.markdown().contains("CIT-"));
        assertTrue(report.markdown().contains("由本地 MATLAB R2023b 执行"));
    }

    @Test void neverClaimsMatlabExecutionForMockData() throws Exception {
        ExperimentReportData source = ReportTestSupport.fixture(root).data();
        ExperimentReportData mock = ReportTestSupport.withExecutionEnvironment(
                source, true, "R2023b", "mock");
        String markdown = new TemplateExperimentReportGenerator().generate(mock).markdown();

        assertTrue(markdown.contains("由内置确定性 Mock Runner 执行，未启动 MATLAB"));
        assertTrue(!markdown.contains("由本地 MATLAB"));
    }
}
