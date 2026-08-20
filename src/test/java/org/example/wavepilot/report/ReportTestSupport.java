package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReportTestSupport {
    static final String CSV = "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,sampleCount,randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion\n"
            + "32,15,0,10,10,1,50,20,15,0,0,0.01,1.0.0\n"
            + "32,15,0.05,9,10,0.9,50,20,14.9,0.1,-0.1,0.01,1.0.0\n"
            + "32,15,0.1,6,10,0.6,50,20,15.1,0.7,0.1,0.01,1.0.0\n"
            + "64,30,0,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.05,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.1,5,10,0.5,50,20,31,1,1,0.01,1.0.0\n";

    private ReportTestSupport() { }

    static Fixture fixture(Path root) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64), 0, 0.1, 0.05, 50, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "report fixture");
        ExperimentPlan plan = new ExperimentPlan("PLAN-REPORT", spec,
                "polar-k-identification-simple-v1", 6, List.of("RUN", "VALIDATE"), Instant.now());
        ExperimentJob job = new ExperimentJob("JOB-REPORT-1", spec, plan);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "validated");
        registry.writeJson(job.getJobId(), ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
        registry.writeJson(job.getJobId(), ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
        Path csv = registry.createJobDirectory(job.getJobId()).resolve("accuracy.csv");
        Files.writeString(csv, CSV);
        registry.register(job.getJobId(), ArtifactType.ACCURACY_CSV, csv);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("experimentType", "POLAR_CODE_K_IDENTIFICATION");
        summary.put("algorithmName", "polar-bsc-binomial-k-baseline");
        summary.put("algorithmVersion", "1.0.0");
        summary.put("classification", "SIMPLIFIED_BASELINE");
        summary.put("mock", false);
        summary.put("algorithmValidated", false);
        summary.put("minAccuracy", 0.5);
        summary.put("maxAccuracy", 1.0);
        summary.put("meanAccuracy", 5.0 / 6.0);
        summary.put("matlabVersion", "R2023b");
        summary.put("runnerType", "local-matlab");
        registry.writeJson(job.getJobId(), ArtifactType.SUMMARY_JSON, "summary.json", summary);
        registry.markJobValidated(job.getJobId(), "local-matlab", false, false,
                "SIMPLIFIED_BASELINE", "polar-k-identification-simple-v1", "1.0.0");
        ReportDataAssembler assembler = new ReportDataAssembler(registry, mapper);
        ReportCitationValidator validator = new ReportCitationValidator(registry, mapper);
        return new Fixture(job, registry, mapper, assembler, validator);
    }

    static ExperimentReportData withCitations(ExperimentReportData data, List<ArtifactCitation> citations) {
        return new ExperimentReportData(data.jobId(), data.experimentType(), data.algorithmName(),
                data.algorithmVersion(), data.classification(), data.mock(), data.algorithmValidated(),
                data.codeLengths(), data.errorRateRange(), data.sampleCount(), data.monteCarloTimes(),
                data.randomSeed(), data.totalPoints(), data.accuracySummary(), data.codeLengthTrends(),
                data.accuracyPoints(), data.matlabVersion(), data.runnerType(), data.templateVersion(),
                data.artifacts(), data.configurationCitationIds(), citations, data.conclusions());
    }

    static ExperimentReportData withConclusions(ExperimentReportData data, List<ReportConclusion> conclusions) {
        return new ExperimentReportData(data.jobId(), data.experimentType(), data.algorithmName(),
                data.algorithmVersion(), data.classification(), data.mock(), data.algorithmValidated(),
                data.codeLengths(), data.errorRateRange(), data.sampleCount(), data.monteCarloTimes(),
                data.randomSeed(), data.totalPoints(), data.accuracySummary(), data.codeLengthTrends(),
                data.accuracyPoints(), data.matlabVersion(), data.runnerType(), data.templateVersion(),
                data.artifacts(), data.configurationCitationIds(), data.citations(), conclusions);
    }

    static ExperimentReportData withExecutionEnvironment(ExperimentReportData data, boolean mock,
                                                          String matlabVersion, String runnerType) {
        return new ExperimentReportData(data.jobId(), data.experimentType(), data.algorithmName(),
                data.algorithmVersion(), data.classification(), mock, data.algorithmValidated(),
                data.codeLengths(), data.errorRateRange(), data.sampleCount(), data.monteCarloTimes(),
                data.randomSeed(), data.totalPoints(), data.accuracySummary(), data.codeLengthTrends(),
                data.accuracyPoints(), matlabVersion, runnerType, data.templateVersion(),
                data.artifacts(), data.configurationCitationIds(), data.citations(), data.conclusions());
    }

    record Fixture(ExperimentJob job, ArtifactRegistry registry, ObjectMapper mapper,
                   ReportDataAssembler assembler, ReportCitationValidator validator) {
        List<ArtifactRecord> artifacts() { return registry.listByJobId(job.getJobId()); }
        ExperimentReportData data() { return assembler.assemble(job, artifacts()); }
    }
}
