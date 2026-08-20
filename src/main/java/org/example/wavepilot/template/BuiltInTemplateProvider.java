package org.example.wavepilot.template;

import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Registers the classpath built-in templates (polar-k templates) as formal registry
 * records. They are operationalValidated because the real MATLAB smoke ran on them, but
 * algorithmValidated stays false.
 */
@Component
public class BuiltInTemplateProvider {

    public List<TemplateRecord> builtInRecords() {
        Instant now = Instant.now();
        MatlabTemplateCatalog.MatlabTemplate simple = MatlabTemplateCatalog.require(
                MatlabTemplateCatalog.SIMPLE_TEMPLATE);
        MatlabTemplateCatalog.MatlabTemplate fixture = MatlabTemplateCatalog.require(
                MatlabTemplateCatalog.INTEGRATION_FIXTURE);
        return List.of(
                record(simple, "极化码码维数识别（简化基线）", "SIMPLIFIED_BASELINE", now),
                record(fixture, "极化码集成 fixture（仅链路验证）", "INTEGRATION_FIXTURE", now));
    }

    private TemplateRecord record(MatlabTemplateCatalog.MatlabTemplate template, String displayName,
                                  String classification, Instant now) {
        return new TemplateRecord(
                template.version(),
                template.experimentType().name().toLowerCase(),
                displayName,
                template.version(),
                "run_experiment",
                "classpath 内置模板，经真实 MATLAB smoke 验证可运行；不是算法验证",
                TemplateSource.BUILT_IN,
                TemplateStatus.ACTIVE,
                classification,
                true,
                false,
                now,
                now,
                "built-in",
                "built-in",
                template.version(),
                List.of("codeLengths", "errorRateStart", "errorRateEnd", "errorRateStep",
                        "sampleCount", "monteCarloTimes", "randomSeed"),
                List.of("ACCURACY_CSV", "SUMMARY_JSON", "RUN_LOG"));
    }
}
