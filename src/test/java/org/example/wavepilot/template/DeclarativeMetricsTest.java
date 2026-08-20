package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.report.DeclarativeMetricsExtractor;
import org.example.wavepilot.report.ExperimentMetricsExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeclarativeMetricsTest {

    @TempDir Path root;

    @Test
    void extractsDeclaredAggregationsFromTheCsv() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ArtifactRegistry registry = new ArtifactRegistry(root.toString(), mapper);
        ExperimentSpec spec = new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG), "metrics test",
                DeclarativeTestSupport.DEMO_TYPE_ID, Map.of());
        ExperimentPlan plan = new ExperimentPlan("PLAN-DEMO", spec, "demo-ber-awgn", 3,
                List.of("RUN"), Instant.now());
        ExperimentJob job = new ExperimentJob("JOB-DEMO-2", spec, plan);
        job.changeStatus(ExperimentStatus.SUCCEEDED, "fixture");
        Path csv = registry.createJobDirectory(job.getJobId()).resolve("accuracy.csv");
        Files.writeString(csv, "ebNo,berSim,berTheory\n"
                + "0,0.1,0.09\n1,0.2,0.21\n2,0.3,0.28\n", StandardCharsets.UTF_8);
        registry.register(job.getJobId(), ArtifactType.ACCURACY_CSV, csv);
        Path summary = registry.createJobDirectory(job.getJobId()).resolve("summary.json");
        Files.writeString(summary, "{}", StandardCharsets.UTF_8);
        registry.register(job.getJobId(), ArtifactType.SUMMARY_JSON, summary);

        ExperimentMetricsExtractor.ExtractedMetrics extracted =
                new DeclarativeMetricsExtractor(DeclarativeTestSupport.registryWithDemo(), registry, mapper)
                        .extract(registry, job, registry.listByJobId(job.getJobId()));

        assertEquals(3, extracted.rows().size());
        assertEquals(0.1, extracted.minAccuracy(), 1.0e-12);
        assertEquals(0.3, extracted.maxAccuracy(), 1.0e-12);
        assertEquals(0.2, extracted.meanAccuracy(), 1.0e-12);

        JsonNode summaryNode = extracted.summary();
        JsonNode metricValues = summaryNode.get("metricValues");
        assertTrue(metricValues.isArray() && metricValues.size() == 2);
        JsonNode meanBer = metricValues.get(0);
        assertEquals("meanBer", meanBer.get("metricName").asText());
        assertEquals(0.2, meanBer.get("value").asDouble(), 1.0e-12);
        assertEquals(0.1, metricValues.get(1).get("value").asDouble(), 1.0e-12,
                "minBer must be the minimum of berSim");
        assertTrue(summaryNode.get("experimentType").asText().equals("demo-ber-awgn"));
        assertEquals("SIMULATION_BASELINE", summaryNode.get("classification").asText());
        assertEquals(false, summaryNode.get("algorithmValidated").asBoolean());
    }
}
