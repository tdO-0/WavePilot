package org.example.wavepilot.experiment.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.example.wavepilot.runner.ProducedArtifact;
import org.example.wavepilot.runner.RunnerStatus;
import org.example.wavepilot.runner.MatlabTemplateCatalog;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ResultValidator {

    private static final double SUMMARY_TOLERANCE = 0.000_001d;

    private final ObjectMapper objectMapper;
    private final ExperimentSpecValidator specValidator;
    private final Map<ExperimentType, ExperimentResultContractValidator> contractValidators;
    private final Map<String, ExperimentResultContractValidator> contractByTemplate;
    private final org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry;

    public ResultValidator(ObjectMapper objectMapper, ExperimentSpecValidator specValidator) {
        this(objectMapper, specValidator, null);
    }

    @Autowired
    public ResultValidator(ObjectMapper objectMapper, ExperimentSpecValidator specValidator,
                           org.example.wavepilot.template.definition.ExperimentDefinitionRegistry definitionRegistry) {
        this.objectMapper = objectMapper;
        this.specValidator = specValidator;
        this.definitionRegistry = definitionRegistry;
        Map<ExperimentType, ExperimentResultContractValidator> registered = new EnumMap<>(ExperimentType.class);
        registered.put(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                new RealPolarAlgorithmResultValidator(objectMapper, specValidator));
        this.contractValidators = Map.copyOf(registered);
        Map<String, ExperimentResultContractValidator> byTemplate = new java.util.LinkedHashMap<>();
        byTemplate.put(MatlabTemplateCatalog.SIMPLE_TEMPLATE,
                contractValidators.get(ExperimentType.POLAR_CODE_K_IDENTIFICATION));
        this.contractByTemplate = Map.copyOf(byTemplate);
    }

    public ValidationResult validate(ExperimentJob job, RunnerStatus runnerStatus,
                                     List<ProducedArtifact> artifacts) {
        List<String> errors = new ArrayList<>();
        if (runnerStatus == null || runnerStatus.exitCode() == null || runnerStatus.exitCode() != 0) {
            errors.add("Runner exit code must be 0");
        }

        Map<ArtifactType, Path> byType = new EnumMap<>(ArtifactType.class);
        if (artifacts != null) {
            for (ProducedArtifact artifact : artifacts) {
                if (artifact != null) {
                    byType.put(artifact.type(), artifact.path());
                }
            }
        }
        requireFile(byType, ArtifactType.ACCURACY_CSV, errors);
        requireFile(byType, ArtifactType.SUMMARY_JSON, errors);
        requireFile(byType, ArtifactType.RUN_LOG, errors);
        if (job.getGenericSpec() == null && job.getSpec().outputTypes().contains(OutputType.MAT_RESULT)) {
            requireFile(byType, ArtifactType.MAT_RESULT, errors);
        }
        if (job.getGenericSpec() == null && job.getSpec().outputTypes().contains(OutputType.ACCURACY_CURVE)) {
            requireFile(byType, ArtifactType.ACCURACY_CURVE, errors);
        }
        if (!errors.isEmpty()) {
            return ValidationResult.failure(errors, List.of());
        }

        // Generic (declarative-template) jobs validate against the template's declarative
        // contract; they have no legacy polar experiment type at all.
        if (job.getGenericSpec() != null) {
            if (definitionRegistry == null) {
                errors.add("Declarative result contract requires the definition registry");
            } else {
                new DeclarativeResultContractValidator(definitionRegistry, objectMapper)
                        .validate(job, byType, errors);
            }
            return errors.isEmpty()
                    ? ValidationResult.success(List.of())
                    : ValidationResult.failure(errors, List.of());
        }

        ExperimentType type = job.getSpec().experimentType();
        String templateVersion = job.getPlan().experimentTemplateVersion();
        ExperimentResultContractValidator contract = contractByTemplate.get(templateVersion);
        if (contract == null && definitionRegistry != null
                && job.getSpec().experimentTypeId() != null) {
            contract = new DeclarativeResultContractValidator(definitionRegistry, objectMapper);
        }
        if (contract != null) {
            // The template declares a dedicated result contract (real polar template or a
            // declarative definition): enforce it.
            contract.validate(job, byType, errors);
        } else if (contractValidators.containsKey(type)) {
            // The type is registered but this job runs a template without a dedicated
            // contract (mock runner or integration fixture): enforce the generic 3-column
            // software-loop contract.
            CsvStatistics statistics = validateCsv(job.getSpec(),
                    byType.get(ArtifactType.ACCURACY_CSV), errors);
            validateSummary(byType.get(ArtifactType.SUMMARY_JSON), statistics, errors);
        } else {
            errors.add("Unsupported experiment type for result validation: " + type
                    + "; registered: " + contractValidators.keySet());
        }
        if (byType.containsKey(ArtifactType.MAT_RESULT)) {
            validateMatFile(byType.get(ArtifactType.MAT_RESULT), errors);
        }
        if (byType.containsKey(ArtifactType.ACCURACY_CURVE)) {
            validatePngFile(byType.get(ArtifactType.ACCURACY_CURVE), errors);
        }
        return errors.isEmpty()
                ? ValidationResult.success(List.of())
                : ValidationResult.failure(errors, List.of());
    }

    private void requireFile(Map<ArtifactType, Path> artifacts, ArtifactType type, List<String> errors) {
        Path path = artifacts.get(type);
        if (path == null || !Files.isRegularFile(path)) {
            errors.add("Required artifact is missing: " + type);
        }
    }

    private CsvStatistics validateCsv(ExperimentSpec spec, Path csv, List<String> errors) {
        Set<String> actualPoints = new HashSet<>();
        long rowCount = 0;
        double sum = 0;
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            // Two accepted contracts: the minimal software-loop CSV and the full 13-column
            // polar contract written by the real MATLAB runner (and by the mock runner,
            // which mirrors it so reports work offline too).
            int[] columns = csvColumns(header);
            if (columns == null) {
                errors.add("accuracy.csv header must be codeLength,errorRate,accuracy or the "
                        + "13-column polar contract");
                return new CsvStatistics(0, 0);
            }
            int codeLengthIndex = columns[0];
            int errorRateIndex = columns[1];
            int accuracyIndex = columns[2];
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split(",", -1);
                if (fields.length != columns[3]) {
                    errors.add("accuracy.csv line " + lineNumber + " must contain "
                            + columns[3] + " fields");
                    continue;
                }
                try {
                    int codeLength = Integer.parseInt(fields[codeLengthIndex]);
                    BigDecimal errorRate = new BigDecimal(fields[errorRateIndex]);
                    double accuracy = Double.parseDouble(fields[accuracyIndex]);
                    if (!Double.isFinite(accuracy) || accuracy < 0 || accuracy > 1) {
                        errors.add("accuracy must be finite and between 0 and 1 at line " + lineNumber);
                        continue;
                    }
                    String pointKey = pointKey(codeLength, errorRate);
                    if (!actualPoints.add(pointKey)) {
                        errors.add("Duplicate result point at line " + lineNumber + ": " + pointKey);
                    }
                    rowCount++;
                    sum += accuracy;
                } catch (NumberFormatException e) {
                    errors.add("accuracy.csv contains an invalid number at line " + lineNumber);
                }
            }
        } catch (IOException e) {
            errors.add("Cannot parse accuracy.csv: " + e.getMessage());
        }

        int expectedRatePoints = specValidator.calculateErrorRatePointCount(spec);
        Set<String> expectedPoints = new HashSet<>();
        for (Integer codeLength : spec.codeLengths()) {
            for (int point = 0; point < expectedRatePoints; point++) {
                BigDecimal errorRate = BigDecimal.valueOf(spec.errorRateStart())
                        .add(BigDecimal.valueOf(spec.errorRateStep()).multiply(BigDecimal.valueOf(point)));
                String expectedPoint = pointKey(codeLength, errorRate);
                expectedPoints.add(expectedPoint);
                if (!actualPoints.contains(expectedPoint)) {
                    errors.add("Missing result point: codeLength=" + codeLength + ", errorRate="
                            + errorRate.stripTrailingZeros().toPlainString());
                }
            }
        }
        actualPoints.stream()
                .filter(point -> !expectedPoints.contains(point))
                .sorted()
                .forEach(point -> errors.add("Unexpected result point: " + point));
        return new CsvStatistics(rowCount, rowCount == 0 ? 0 : sum / rowCount);
    }

    /** Resolves the column indexes of an accepted CSV contract; null if unsupported. */
    private int[] csvColumns(String header) {
        if (header == null) return null;
        String[] names = header.split(",", -1);
        int codeLength = -1;
        int errorRate = -1;
        int accuracy = -1;
        for (int index = 0; index < names.length; index++) {
            switch (names[index].trim()) {
                case "codeLength" -> codeLength = index;
                case "errorRate" -> errorRate = index;
                case "accuracy" -> accuracy = index;
                default -> { /* other columns are ignored */ }
            }
        }
        if (codeLength < 0 || errorRate < 0 || accuracy < 0) return null;
        return new int[]{codeLength, errorRate, accuracy, names.length};
    }

    private void validateSummary(Path summary, CsvStatistics statistics, List<String> errors) {
        try {
            JsonNode node = objectMapper.readTree(summary.toFile());
            JsonNode mockNode = node.get("mock");
            JsonNode rowCountNode = node.get("rowCount");
            JsonNode averageNode = node.get("averageAccuracy");
            if (mockNode == null || !mockNode.isBoolean()) {
                errors.add("summary.json mock boundary must be an explicit boolean");
            }
            if (rowCountNode == null || rowCountNode.asLong(-1) != statistics.rowCount()) {
                errors.add("summary.json rowCount does not match accuracy.csv");
            }
            if (averageNode == null || !averageNode.isNumber()
                    || !Double.isFinite(averageNode.asDouble())
                    || Math.abs(averageNode.asDouble() - statistics.averageAccuracy()) > SUMMARY_TOLERANCE) {
                errors.add("summary.json averageAccuracy does not match accuracy.csv");
            }
        } catch (IOException e) {
            errors.add("Cannot parse summary.json: " + e.getMessage());
        }
    }

    private void validateMatFile(Path matFile, List<String> errors) {
        byte[] header = new byte[128];
        try (InputStream input = Files.newInputStream(matFile)) {
            int count = input.read(header);
            boolean levelFive = count >= 19
                    && new String(header, 0, 19, StandardCharsets.US_ASCII).startsWith("MATLAB 5.0 MAT-file");
            boolean hdf5 = count >= 8
                    && header[0] == (byte) 0x89 && header[1] == 'H' && header[2] == 'D' && header[3] == 'F'
                    && header[4] == '\r' && header[5] == '\n' && header[6] == 0x1A && header[7] == '\n';
            if (!levelFive && !hdf5) {
                errors.add("result.mat does not have a recognized MATLAB MAT-file signature");
            }
        } catch (IOException e) {
            errors.add("Cannot inspect result.mat: " + e.getMessage());
        }
    }

    private void validatePngFile(Path pngFile, List<String> errors) {
        byte[] expected = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        byte[] actual = new byte[expected.length];
        try (InputStream input = Files.newInputStream(pngFile)) {
            int count = input.read(actual);
            if (count != expected.length || !java.util.Arrays.equals(expected, actual)) {
                errors.add("accuracy-curve.png does not have a valid PNG signature");
                return;
            }
        } catch (IOException e) {
            errors.add("Cannot inspect accuracy-curve.png: " + e.getMessage());
            return;
        }
        try {
            BufferedImage image = ImageIO.read(pngFile.toFile());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                errors.add("accuracy-curve.png is not a decodable image with valid dimensions");
            } else if (isUniformImage(image)) {
                errors.add("accuracy-curve.png must contain plotted Accuracy data");
            }
        } catch (IOException e) {
            errors.add("Cannot decode accuracy-curve.png: " + e.getMessage());
        }
    }

    private boolean isUniformImage(BufferedImage image) {
        int first = image.getRGB(0, 0);
        int xStep = Math.max(1, image.getWidth() / 100);
        int yStep = Math.max(1, image.getHeight() / 100);
        for (int y = 0; y < image.getHeight(); y += yStep) {
            for (int x = 0; x < image.getWidth(); x += xStep) {
                if (image.getRGB(x, y) != first) return false;
            }
        }
        return true;
    }

    private String pointKey(int codeLength, BigDecimal errorRate) {
        return codeLength + "|" + errorRate.stripTrailingZeros().toPlainString();
    }

    private record CsvStatistics(long rowCount, double averageAccuracy) {
    }
}
