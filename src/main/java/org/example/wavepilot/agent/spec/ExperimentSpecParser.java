package org.example.wavepilot.agent.spec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ExperimentSpecParser {

    private static final List<OutputType> DEFAULT_OUTPUTS = List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG);

    private final ExperimentSpecExtractionModel extractionModel;
    private final ObjectMapper objectMapper;
    private final ExperimentSpecValidator validator;
    private final long defaultRandomSeed;

    public ExperimentSpecParser(ExperimentSpecExtractionModel extractionModel, ObjectMapper objectMapper,
                                ExperimentSpecValidator validator,
                                @Value("${wavepilot.spec.default-random-seed:20}") long defaultRandomSeed) {
        this.extractionModel = extractionModel;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.defaultRandomSeed = defaultRandomSeed;
    }

    public ExperimentSpecParseResult parse(String message) {
        if (message == null || message.isBlank()) {
            return invalid("EMPTY_MESSAGE", "Experiment request message is required");
        }
        final String modelOutput;
        try {
            modelOutput = extractionModel.extract(buildPrompt(message));
        } catch (RuntimeException e) {
            return invalid("MODEL_UNAVAILABLE", e.getMessage());
        }

        ModelCandidate candidate;
        try {
            candidate = objectMapper.readValue(extractJsonObject(modelOutput), ModelCandidate.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return invalid("MODEL_OUTPUT_INVALID_JSON", "Model output is not valid ExperimentSpec JSON");
        }
        if (Boolean.FALSE.equals(candidate.relevant())) {
            return invalid("UNRELATED_REQUEST", "The message is not a supported communication experiment request");
        }

        List<String> missing = missingFields(candidate);
        if (!missing.isEmpty()) {
            List<String> questions = missing.stream().map(this::clarificationQuestion).toList();
            return new ExperimentSpecParseResult(ExperimentSpecParseStatus.NEEDS_CLARIFICATION, null,
                    missing, questions, ValidationResult.failure(
                    missing.stream().map(field -> "Missing required field: " + field).toList(), List.of()),
                    List.of(), List.of());
        }

        List<String> warnings = new ArrayList<>();
        List<String> defaulted = new ArrayList<>();
        long seed = candidate.randomSeed() == null ? defaultRandomSeed : candidate.randomSeed();
        if (candidate.randomSeed() == null) {
            defaulted.add("randomSeed");
            warnings.add("randomSeed defaulted to " + defaultRandomSeed);
        }
        List<OutputType> outputs = candidate.outputTypes();
        if (outputs == null || outputs.isEmpty()) {
            outputs = DEFAULT_OUTPUTS;
            defaulted.add("outputTypes");
            warnings.add("outputTypes defaulted to ACCURACY_CSV and RUN_LOG");
        }
        ExperimentSpec spec = new ExperimentSpec(candidate.experimentType(), candidate.codeLengths(),
                candidate.errorRateStart(), candidate.errorRateEnd(), candidate.errorRateStep(),
                candidate.sampleCount(), candidate.monteCarloTimes(), seed, outputs,
                candidate.description() == null || candidate.description().isBlank()
                        ? message.trim() : candidate.description().trim());
        ValidationResult validation = validator.validate(spec);
        warnings.addAll(validation.warnings());
        return new ExperimentSpecParseResult(
                validation.valid() ? ExperimentSpecParseStatus.COMPLETE : ExperimentSpecParseStatus.INVALID,
                spec, List.of(), List.of(), validation, warnings, defaulted);
    }

    public ValidationResult validate(ExperimentSpec spec) {
        return validator.validate(spec);
    }

    public ExperimentSpecParseResult validateJson(String specJson) {
        try {
            ExperimentSpec spec = objectMapper.readValue(specJson, ExperimentSpec.class);
            ValidationResult result = validator.validate(spec);
            return new ExperimentSpecParseResult(result.valid() ? ExperimentSpecParseStatus.COMPLETE
                    : ExperimentSpecParseStatus.INVALID, spec, List.of(), List.of(), result,
                    result.warnings(), List.of());
        } catch (JsonProcessingException e) {
            return invalid("INVALID_SPEC_JSON", "ExperimentSpec JSON cannot be parsed");
        }
    }

    public String buildPrompt(String message) {
        return """
                Extract a communication experiment request as one JSON object and output JSON only.
                Do not invent missing experimental parameters. Use null for every missing field.
                Set relevant=false when the request is unrelated to communication experiments.
                Supported experimentType: POLAR_CODE_K_IDENTIFICATION.
                Schema: {"relevant":boolean,"experimentType":string|null,"codeLengths":number[]|null,
                "errorRateStart":number|null,"errorRateEnd":number|null,"errorRateStep":number|null,
                "sampleCount":integer|null,"monteCarloTimes":integer|null,"randomSeed":integer|null,
                "outputTypes":string[]|null,"description":string|null}.
                Never output MATLAB code, shell commands, explanations, or Markdown.
                User message: %s
                """.formatted(message);
    }

    private List<String> missingFields(ModelCandidate candidate) {
        List<String> missing = new ArrayList<>();
        if (candidate.experimentType() == null) missing.add("experimentType");
        if (candidate.codeLengths() == null || candidate.codeLengths().isEmpty()) missing.add("codeLengths");
        if (candidate.errorRateStart() == null) missing.add("errorRateStart");
        if (candidate.errorRateEnd() == null) missing.add("errorRateEnd");
        if (candidate.errorRateStep() == null) missing.add("errorRateStep");
        if (candidate.sampleCount() == null) missing.add("sampleCount");
        if (candidate.monteCarloTimes() == null) missing.add("monteCarloTimes");
        return missing;
    }

    private String clarificationQuestion(String field) {
        return switch (field) {
            case "experimentType" -> "请确认实验类型；当前支持极化码码维数识别实验。";
            case "codeLengths" -> "请提供至少一个码长（必须是大于等于 32 的 2 的幂）。";
            case "errorRateStart" -> "误码率扫描的起始值是多少？";
            case "errorRateEnd" -> "误码率扫描的结束值是多少？";
            case "errorRateStep" -> "误码率扫描步长是多少？";
            case "sampleCount" -> "每组需要多少样本或帧？";
            case "monteCarloTimes" -> "需要执行多少次蒙特卡洛实验？";
            default -> "请补充参数：" + field;
        };
    }

    private String extractJsonObject(String output) {
        if (output == null) throw new IllegalArgumentException("Model output is empty");
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("No JSON object in model output");
        return output.substring(start, end + 1);
    }

    private ExperimentSpecParseResult invalid(String code, String message) {
        ValidationResult result = ValidationResult.failure(List.of(code + ": " + message), List.of());
        return new ExperimentSpecParseResult(ExperimentSpecParseStatus.INVALID, null, List.of(),
                List.of(), result, List.of(), List.of());
    }

    private record ModelCandidate(
            Boolean relevant,
            ExperimentType experimentType,
            List<Integer> codeLengths,
            Double errorRateStart,
            Double errorRateEnd,
            Double errorRateStep,
            Integer sampleCount,
            Integer monteCarloTimes,
            Long randomSeed,
            List<OutputType> outputTypes,
            String description) {
    }
}
