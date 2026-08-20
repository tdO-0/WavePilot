package org.example.wavepilot.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a controlled SUCCEEDED job with the full 13-column real polar CSV contract so the
 * citation and grounding executors can verify the report chain without MATLAB.
 */
@Component
public class EvaluationFixtureFactory {

    static final String FIXTURE_CSV = "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,"
            + "sampleCount,randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion\n"
            + "32,15,0,10,10,1,50,20,15,0,0,0.01,1.0.0\n"
            + "32,15,0.05,9,10,0.9,50,20,14.9,0.1,-0.1,0.01,1.0.0\n"
            + "32,15,0.1,6,10,0.6,50,20,15.1,0.7,0.1,0.01,1.0.0\n"
            + "64,30,0,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.05,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.1,5,10,0.5,50,20,31,1,1,0.01,1.0.0\n";

    private final ExperimentJobRepository jobRepository;
    private final ArtifactRegistry registry;
    private final ObjectMapper objectMapper;

    public EvaluationFixtureFactory(ExperimentJobRepository jobRepository, ArtifactRegistry registry,
                                    ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    public ExperimentJob buildSucceededJob() {
        String jobId = "EVALFIX-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64), 0.0, 0.1, 0.05, 50, 10, 20,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "evaluation citation fixture");
        ExperimentPlan plan = new ExperimentPlan("PLAN-" + jobId, spec,
                "polar-k-identification-simple-v1", 6, List.of("RUN", "VALIDATE"), Instant.now());
        ExperimentJob job = new ExperimentJob(jobId, spec, plan);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "evaluation fixture");
        jobRepository.save(job);
        try {
            registry.writeJson(jobId, ArtifactType.EXPERIMENT_SPEC, "experiment-spec.json", spec);
            registry.writeJson(jobId, ArtifactType.EXPERIMENT_PLAN, "experiment-plan.json", plan);
            Path csv = registry.createJobDirectory(jobId).resolve("accuracy.csv");
            Files.writeString(csv, FIXTURE_CSV, StandardCharsets.UTF_8);
            registry.register(jobId, ArtifactType.ACCURACY_CSV, csv);
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
            registry.writeJson(jobId, ArtifactType.SUMMARY_JSON, "summary.json", summary);
            registry.markJobValidated(jobId, "local-matlab", false, false,
                    "SIMPLIFIED_BASELINE", "polar-k-identification-simple-v1", "1.0.0");
            return job;
        } catch (Exception e) {
            throw new EvaluationException("Cannot build evaluation fixture job " + jobId, e);
        }
    }
}
