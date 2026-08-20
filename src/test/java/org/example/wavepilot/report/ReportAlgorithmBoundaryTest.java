package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportAlgorithmBoundaryTest {
    @TempDir Path root;

    @Test void preservesSimplifiedBaselineAndValidationBoundary() throws Exception {
        ExperimentReportDocument report = new TemplateExperimentReportGenerator()
                .generate(ReportTestSupport.fixture(root).data());
        assertTrue(report.markdown().contains("SIMPLIFIED_BASELINE"));
        assertTrue(report.markdown().contains("algorithmValidated=false"));
        assertTrue(report.markdown().contains("mock=false"));
        assertTrue(report.markdown().contains("不能作为论文复现结果"));
        assertFalse(report.markdown().contains("本算法已经科研验证"));
    }
}
