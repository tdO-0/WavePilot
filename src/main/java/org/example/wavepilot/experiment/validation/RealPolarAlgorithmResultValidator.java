package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.runner.MatlabTemplateCatalog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real polar CSV/summary/MAT contract for the polar-k-identification templates. */
public final class RealPolarAlgorithmResultValidator implements ExperimentResultContractValidator {

    static final String CSV_HEADER = "codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,"
            + "sampleCount,randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion";
    private static final double TOLERANCE = 0.000_000_001d;
    private static final String MANIFEST_RESOURCE = "/matlab/templates/"
            + MatlabTemplateCatalog.SIMPLE_TEMPLATE + "/TEMPLATE_MANIFEST.json";

    private final ObjectMapper objectMapper;
    private final ExperimentSpecValidator specValidator;
    private final JsonNode manifest;

    public RealPolarAlgorithmResultValidator(ObjectMapper objectMapper, ExperimentSpecValidator specValidator) {
        this.objectMapper = objectMapper;
        this.specValidator = specValidator;
        this.manifest = readManifest();
    }

    @Override
    public ExperimentType experimentType() {
        return ExperimentType.POLAR_CODE_K_IDENTIFICATION;
    }

    @Override
    public void validate(ExperimentJob job, Map<ArtifactType, Path> artifacts, List<String> errors) {
        PolarCsvStatistics statistics = validateCsv(job.getSpec(),
                artifacts.get(ArtifactType.ACCURACY_CSV), errors);
        validateSummary(job, artifacts.get(ArtifactType.SUMMARY_JSON), statistics, errors);
        Path mat = artifacts.get(ArtifactType.MAT_RESULT);
        if (mat != null) {
            validateMatVariables(mat, errors);
        }
    }

    private PolarCsvStatistics validateCsv(ExperimentSpec spec, Path csv, List<String> errors) {
        Set<String> actualPoints = new HashSet<>();
        long rowCount = 0;
        double sum = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!CSV_HEADER.equals(header)) {
                errors.add("Real polar accuracy.csv header does not match the Phase 4.5 contract");
                return PolarCsvStatistics.empty();
            }
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] fields = line.split(",", -1);
                if (fields.length != 13) {
                    errors.add("Real polar accuracy.csv line " + lineNumber + " must contain 13 fields");
                    continue;
                }
                try {
                    int codeLength = exactInt(fields[0], "codeLength");
                    int trueK = exactInt(fields[1], "trueK");
                    BigDecimal errorRate = new BigDecimal(fields[2]);
                    int correctCount = exactInt(fields[3], "correctCount");
                    int monteCarloTimes = exactInt(fields[4], "monteCarloTimes");
                    double accuracy = Double.parseDouble(fields[5]);
                    int sampleCount = exactInt(fields[6], "sampleCount");
                    long randomSeed = exactLong(fields[7], "randomSeed");
                    double meanEstimatedK = Double.parseDouble(fields[8]);
                    double mae = Double.parseDouble(fields[9]);
                    double bias = Double.parseDouble(fields[10]);
                    double runtimeSeconds = Double.parseDouble(fields[11]);
                    String algorithmVersion = unquote(fields[12]);

                    int expectedTrueK = Math.multiplyExact(15, codeLength) / 32;
                    if (15 * codeLength % 32 != 0 || trueK != expectedTrueK) {
                        errors.add("trueK must equal 15N/32 at line " + lineNumber);
                    }
                    if (monteCarloTimes != spec.monteCarloTimes()) {
                        errors.add("monteCarloTimes does not match ExperimentSpec at line " + lineNumber);
                    }
                    if (correctCount < 0 || correctCount > monteCarloTimes) {
                        errors.add("correctCount must be between 0 and monteCarloTimes at line " + lineNumber);
                    }
                    double expectedAccuracy = monteCarloTimes == 0 ? Double.NaN
                            : (double) correctCount / monteCarloTimes;
                    if (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 1
                            || Math.abs(accuracy - expectedAccuracy) > TOLERANCE) {
                        errors.add("accuracy must equal correctCount/monteCarloTimes at line " + lineNumber);
                    }
                    if (sampleCount != spec.sampleCount()) {
                        errors.add("sampleCount does not match ExperimentSpec at line " + lineNumber);
                    }
                    if (randomSeed != spec.randomSeed()) {
                        errors.add("randomSeed does not match ExperimentSpec at line " + lineNumber);
                    }
                    if (!Double.isFinite(meanEstimatedK) || !Double.isFinite(mae) || mae < 0
                            || !Double.isFinite(bias) || !Double.isFinite(runtimeSeconds)
                            || runtimeSeconds < 0) {
                        errors.add("Real polar diagnostic metrics are invalid at line " + lineNumber);
                    }
                    if (!manifest.path("algorithmVersion").asText().equals(algorithmVersion)) {
                        errors.add("algorithmVersion does not match TEMPLATE_MANIFEST.json at line " + lineNumber);
                    }

                    String point = pointKey(codeLength, errorRate);
                    if (!actualPoints.add(point)) {
                        errors.add("Duplicate result point at line " + lineNumber + ": " + point);
                    }
                    rowCount++;
                    sum += accuracy;
                    min = Math.min(min, accuracy);
                    max = Math.max(max, accuracy);
                } catch (ArithmeticException | NumberFormatException e) {
                    errors.add("Real polar accuracy.csv contains an invalid value at line " + lineNumber);
                }
            }
        } catch (IOException e) {
            errors.add("Cannot parse real polar accuracy.csv: " + e.getMessage());
        }

        validatePointGrid(spec, actualPoints, errors);
        return rowCount == 0 ? PolarCsvStatistics.empty()
                : new PolarCsvStatistics(rowCount, min, max, sum / rowCount);
    }

    private void validatePointGrid(ExperimentSpec spec, Set<String> actualPoints, List<String> errors) {
        int expectedRatePoints = specValidator.calculateErrorRatePointCount(spec);
        Set<String> expectedPoints = new HashSet<>();
        for (Integer codeLength : spec.codeLengths()) {
            for (int point = 0; point < expectedRatePoints; point++) {
                BigDecimal errorRate = BigDecimal.valueOf(spec.errorRateStart())
                        .add(BigDecimal.valueOf(spec.errorRateStep()).multiply(BigDecimal.valueOf(point)));
                String key = pointKey(codeLength, errorRate);
                expectedPoints.add(key);
                if (!actualPoints.contains(key)) {
                    errors.add("Missing result point: " + key);
                }
            }
        }
        actualPoints.stream().filter(point -> !expectedPoints.contains(point)).sorted()
                .forEach(point -> errors.add("Unexpected result point: " + point));
    }

    private void validateSummary(ExperimentJob job, Path summaryFile, PolarCsvStatistics statistics,
                                 List<String> errors) {
        try {
            JsonNode summary = objectMapper.readTree(summaryFile.toFile());
            requireText(summary, "experimentType", "POLAR_CODE_K_IDENTIFICATION", errors);
            requireText(summary, "algorithmName", manifest.path("algorithmName").asText(), errors);
            requireText(summary, "algorithmVersion", manifest.path("algorithmVersion").asText(), errors);
            requireText(summary, "templateVersion", manifest.path("templateVersion").asText(), errors);
            requireText(summary, "runnerType", "local-matlab", errors);
            requireText(summary, "errorRateMeaning", "BSC_BIT_FLIP_PROBABILITY", errors);
            requireText(summary, "trueKRule", "15N/32", errors);
            requireBoolean(summary, "mock", false, errors);
            requireBoolean(summary, "algorithmValidated", false, errors);
            requireBoolean(summary, "success", true, errors);

            long expectedPoints = job.getPlan().totalRuns();
            requireLong(summary, "totalPoints", expectedPoints, errors);
            requireLong(summary, "completedPoints", expectedPoints, errors);
            requireLong(summary, "randomSeed", job.getSpec().randomSeed(), errors);
            requireMetric(summary, "minAccuracy", statistics.minAccuracy(), errors);
            requireMetric(summary, "maxAccuracy", statistics.maxAccuracy(), errors);
            requireMetric(summary, "meanAccuracy", statistics.meanAccuracy(), errors);
            JsonNode runtime = summary.get("totalRuntimeSeconds");
            if (runtime == null || !runtime.isNumber() || !Double.isFinite(runtime.asDouble())
                    || runtime.asDouble() < 0) {
                errors.add("summary.json totalRuntimeSeconds must be finite and non-negative");
            }
            JsonNode matlabVersion = summary.get("matlabVersion");
            if (matlabVersion == null || !matlabVersion.isTextual()
                    || matlabVersion.asText().isBlank()) {
                errors.add("summary.json matlabVersion must be present");
            }
        } catch (IOException e) {
            errors.add("Cannot parse real polar summary.json: " + e.getMessage());
        }
    }

    private void validateMatVariables(Path matFile, List<String> errors) {
        try (InputStream input = Files.newInputStream(matFile)) {
            byte[] content = input.readNBytes(4 * 1024 * 1024);
            for (String variable : List.of("accuracyMatrix", "estimatedKMatrix",
                    "NVec", "errorVec", "trueKVec")) {
                if (!containsAscii(content, variable)) {
                    errors.add("result.mat is missing required variable: " + variable);
                }
            }
        } catch (IOException e) {
            errors.add("Cannot inspect real polar result.mat variables: " + e.getMessage());
        }
    }

    private JsonNode readManifest() {
        try (InputStream input = RealPolarAlgorithmResultValidator.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Real polar TEMPLATE_MANIFEST.json is missing");
            }
            return objectMapper.readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read real polar TEMPLATE_MANIFEST.json", e);
        }
    }

    private void requireText(JsonNode node, String field, String expected, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !expected.equals(value.asText())) {
            errors.add("summary.json " + field + " must equal " + expected);
        }
    }

    private void requireBoolean(JsonNode node, String field, boolean expected, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean() || value.asBoolean() != expected) {
            errors.add("summary.json " + field + " must equal " + expected);
        }
    }

    private void requireLong(JsonNode node, String field, long expected, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || value.asLong() != expected) {
            errors.add("summary.json " + field + " must equal " + expected);
        }
    }

    private void requireMetric(JsonNode node, String field, double expected, List<String> errors) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber() || !Double.isFinite(value.asDouble())
                || Math.abs(value.asDouble() - expected) > TOLERANCE) {
            errors.add("summary.json " + field + " does not match accuracy.csv");
        }
    }

    private int exactInt(String value, String field) {
        long parsed = exactLong(value, field);
        if (parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
            throw new NumberFormatException(field);
        }
        return (int) parsed;
    }

    private long exactLong(String value, String field) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed) || parsed != Math.rint(parsed)
                || parsed < Long.MIN_VALUE || parsed > Long.MAX_VALUE) {
            throw new NumberFormatException(field);
        }
        return (long) parsed;
    }

    private String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String pointKey(int codeLength, BigDecimal errorRate) {
        return codeLength + "|" + errorRate.stripTrailingZeros().toPlainString();
    }

    private boolean containsAscii(byte[] content, String token) {
        byte[] expected = token.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int index = 0; index <= content.length - expected.length; index++) {
            for (int offset = 0; offset < expected.length; offset++) {
                if (content[index + offset] != expected[offset]) continue outer;
            }
            return true;
        }
        return false;
    }

    private record PolarCsvStatistics(long rowCount, double minAccuracy, double maxAccuracy,
                                      double meanAccuracy) {
        private static PolarCsvStatistics empty() {
            return new PolarCsvStatistics(0, 0, 0, 0);
        }
    }
}
