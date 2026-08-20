package org.example.wavepilot.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ReportAgentBoundaryTest {
    @TempDir Path root;

    @Test void rejectsModelIntroducedNumericValue() throws Exception {
        ExperimentReportData data = ReportTestSupport.fixture(root).data();
        ExperimentReportDocument template = new TemplateExperimentReportGenerator().generate(data);
        ReportLanguageModel model = (structured, markdown) -> new ReportLanguageModel.ReportAgentDraft(
                markdown + "\n模型新增不存在的准确率 0.777", structured.conclusions());
        assertThrows(ControlledReportAgent.ReportAgentBoundaryException.class,
                () -> new ControlledReportAgent().rewrite(model, data, template));
    }
}
