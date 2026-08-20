package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RealPolarTestSupport {

    static final String CSV = RealPolarAlgorithmResultValidator.CSV_HEADER + "\n"
            + "32,15,0,10,10,1,50,20,15,0,0,0.01,1.0.0\n"
            + "32,15,0.05,8,10,0.8,50,20,15,0,0,0.01,1.0.0\n"
            + "32,15,0.1,5,10,0.5,50,20,14.8,0.2,-0.2,0.01,1.0.0\n"
            + "64,30,0,10,10,1,50,20,30,0,0,0.01,1.0.0\n"
            + "64,30,0.05,7,10,0.7,50,20,29.9,0.1,-0.1,0.01,1.0.0\n"
            + "64,30,0.1,4,10,0.4,50,20,29.5,0.5,-0.5,0.01,1.0.0\n";
    static final double MEAN_ACCURACY = 4.4 / 6.0;

    private RealPolarTestSupport() {
    }

    static ExperimentSpec spec() {
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32, 64), 0.0, 0.10, 0.05, 50, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.MAT_RESULT,
                        OutputType.ACCURACY_CURVE, OutputType.RUN_LOG),
                "real polar contract test");
    }

    static ExperimentJob job(String jobId) {
        ExperimentSpec spec = spec();
        return new ExperimentJob(jobId, spec,
                new ExperimentPlan("PLAN-" + jobId, spec, MatlabTemplateCatalog.SIMPLE_TEMPLATE,
                        6, List.of("RUN_EXPERIMENT", "VALIDATE_RESULT"), Instant.now()));
    }

    static List<ProducedArtifact> writeValidArtifacts(Path directory, ObjectMapper mapper)
            throws IOException {
        Files.createDirectories(directory);
        Path csv = directory.resolve("accuracy.csv");
        Path summary = directory.resolve("summary.json");
        Path mat = directory.resolve("result.mat");
        Path png = directory.resolve("accuracy-curve.png");
        Path log = directory.resolve("run.log");
        Files.writeString(csv, CSV);
        mapper.writeValue(summary.toFile(), validSummary());
        writeMat(mat);
        BufferedImage image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(5, 5, 0x00FFFFFF);
        ImageIO.write(image, "png", png.toFile());
        Files.writeString(log, "algorithm=polar-bsc-binomial-k-baseline\nmock=false\n");
        return List.of(
                new ProducedArtifact(ArtifactType.ACCURACY_CSV, csv),
                new ProducedArtifact(ArtifactType.SUMMARY_JSON, summary),
                new ProducedArtifact(ArtifactType.MAT_RESULT, mat),
                new ProducedArtifact(ArtifactType.ACCURACY_CURVE, png),
                new ProducedArtifact(ArtifactType.RUN_LOG, log));
    }

    static Map<String, Object> validSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("experimentType", "POLAR_CODE_K_IDENTIFICATION");
        summary.put("algorithmName", MatlabTemplateCatalog.SIMPLE_ALGORITHM_NAME);
        summary.put("algorithmVersion", MatlabTemplateCatalog.SIMPLE_ALGORITHM_VERSION);
        summary.put("templateVersion", MatlabTemplateCatalog.SIMPLE_TEMPLATE);
        summary.put("runnerType", "local-matlab");
        summary.put("mock", false);
        summary.put("algorithmValidated", false);
        summary.put("errorRateMeaning", "BSC_BIT_FLIP_PROBABILITY");
        summary.put("trueKRule", "15N/32");
        summary.put("randomSeed", 20);
        summary.put("totalPoints", 6);
        summary.put("completedPoints", 6);
        summary.put("minAccuracy", 0.4);
        summary.put("maxAccuracy", 1.0);
        summary.put("meanAccuracy", MEAN_ACCURACY);
        summary.put("totalRuntimeSeconds", 0.2);
        summary.put("matlabVersion", "test-matlab");
        summary.put("success", true);
        return summary;
    }

    static RunnerStatus succeededStatus() {
        return new RunnerStatus("MATLAB-REAL-POLAR", RunnerStatus.State.SUCCEEDED,
                100, 6, 6, "done", 0, Instant.now());
    }

    private static void writeMat(Path path) throws IOException {
        byte[] content = new byte[512];
        byte[] signature = "MATLAB 5.0 MAT-file".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(signature, 0, content, 0, signature.length);
        int offset = 128;
        for (String variable : List.of("accuracyMatrix", "estimatedKMatrix",
                "NVec", "errorVec", "trueKVec")) {
            byte[] token = variable.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(token, 0, content, offset, token.length);
            offset += token.length + 4;
        }
        Files.write(path, content);
    }
}
