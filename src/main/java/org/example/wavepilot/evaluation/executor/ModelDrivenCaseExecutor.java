package org.example.wavepilot.evaluation.executor;

import org.example.wavepilot.evaluation.EvaluationCase;
import org.example.wavepilot.evaluation.EvaluationCaseResult;
import org.example.wavepilot.evaluation.EvaluationModel;
import org.example.wavepilot.evaluation.EvaluationToolGuard;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.validation.ExperimentSpecValidator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes the model-dependent cases: spec parsing, missing parameter detection, Java
 * validation of invalid parameters, tool selection and tool security. The platform side
 * (Java validator and the tool safety guard) is always real; only the model is a stub.
 */
@Component
public class ModelDrivenCaseExecutor implements EvaluationCaseExecutor {

    private final ExperimentSpecValidator specValidator;
    private final EvaluationToolGuard toolGuard;

    public ModelDrivenCaseExecutor(ExperimentSpecValidator specValidator, EvaluationToolGuard toolGuard) {
        this.specValidator = specValidator;
        this.toolGuard = toolGuard;
    }

    @Override
    public EvaluationCaseResult execute(EvaluationCase evalCase, EvaluationModel model) {
        try {
            EvaluationModel.EvaluationModelResponse response = model.run(evalCase);
            return switch (evalCase.caseType()) {
                case COMPLETE_SPEC -> completeSpec(evalCase, response);
                case MISSING_PARAMETER -> missingParameter(evalCase, response);
                case INVALID_PARAMETER -> invalidParameter(evalCase, response);
                case TOOL_SELECTION -> toolSelection(evalCase, response);
                case TOOL_SECURITY -> toolSecurity(evalCase, response);
                default -> throw new IllegalArgumentException(
                        "Unsupported case type for the model-driven executor: " + evalCase.caseType());
            };
        } catch (RuntimeException e) {
            return EvaluationCaseResult.failed(evalCase, "Model-driven execution failed: " + e.getMessage());
        }
    }

    private EvaluationCaseResult completeSpec(EvaluationCase evalCase,
                                              EvaluationModel.EvaluationModelResponse response) {
        ExperimentSpec spec = response.parsedSpec();
        ValidationResult validation = specValidator.validate(spec);
        List<String> missing = missingExpectedFields(evalCase, spec);
        String actualResult = validation.valid()
                ? "VALIDATED" : "REJECTED: " + String.join("; ", validation.errors());
        if (!missing.isEmpty()) actualResult += " | missing expected fields: " + missing;
        boolean passed = validation.valid() && missing.isEmpty()
                && actualResult.startsWith(evalCase.expectedResult());
        return result(evalCase, passed, actualResult, null);
    }

    private EvaluationCaseResult missingParameter(EvaluationCase evalCase,
                                                  EvaluationModel.EvaluationModelResponse response) {
        List<String> missing = response.missingParameters();
        String actualResult = "MISSING: " + String.join(", ", missing);
        boolean foundAll = new java.util.HashSet<>(missing).containsAll(evalCase.expectedFields());
        boolean passed = !missing.isEmpty() && foundAll
                && actualResult.startsWith(evalCase.expectedResult());
        return result(evalCase, passed, actualResult, null);
    }

    private EvaluationCaseResult invalidParameter(EvaluationCase evalCase,
                                                  EvaluationModel.EvaluationModelResponse response) {
        ExperimentSpec spec = response.parsedSpec();
        ValidationResult validation = specValidator.validate(spec);
        String actualResult = validation.valid()
                ? "ACCEPTED" : "REJECTED: " + String.join("; ", validation.errors());
        boolean mentionsExpected = validation.errors().stream()
                .anyMatch(error -> evalCase.expectedFields().stream().anyMatch(error::contains));
        boolean passed = !validation.valid() && mentionsExpected
                && actualResult.startsWith(evalCase.expectedResult());
        return result(evalCase, passed, actualResult, null);
    }

    private EvaluationCaseResult toolSelection(EvaluationCase evalCase,
                                               EvaluationModel.EvaluationModelResponse response) {
        String selected = response.selectedTool();
        String actualResult = evalCase.expectedTool().equals(selected)
                ? "TOOL_OK: " + selected : "WRONG_TOOL: " + selected;
        boolean passed = evalCase.expectedTool().equals(selected)
                && actualResult.startsWith(evalCase.expectedResult());
        return result(evalCase, passed, actualResult, selected);
    }

    private EvaluationCaseResult toolSecurity(EvaluationCase evalCase,
                                              EvaluationModel.EvaluationModelResponse response) {
        String selected = response.selectedTool();
        EvaluationToolGuard.Decision decision = toolGuard.evaluate(selected, evalCase.forbiddenTools());
        String expected = evalCase.expectedStatus();
        boolean passed;
        if ("ALLOWED".equals(expected)) {
            passed = decision.allowed() && evalCase.expectedTool().equals(selected);
        } else {
            passed = !decision.allowed() && evalCase.forbiddenTools().contains(selected);
        }
        passed = passed && decision.message().startsWith(evalCase.expectedResult());
        return result(evalCase, passed, decision.message(), selected);
    }

    private List<String> missingExpectedFields(EvaluationCase evalCase, ExperimentSpec spec) {
        List<String> missing = new ArrayList<>();
        for (String field : evalCase.expectedFields()) {
            if (!hasField(spec, field)) missing.add(field);
        }
        return missing;
    }

    private boolean hasField(ExperimentSpec spec, String field) {
        return switch (field) {
            case "codeLengths" -> spec.codeLengths() != null && !spec.codeLengths().isEmpty();
            case "errorRateStart" -> Double.isFinite(spec.errorRateStart());
            case "errorRateEnd" -> Double.isFinite(spec.errorRateEnd());
            case "errorRateStep" -> Double.isFinite(spec.errorRateStep());
            case "sampleCount" -> spec.sampleCount() > 0;
            case "monteCarloTimes" -> spec.monteCarloTimes() > 0;
            case "randomSeed" -> spec.randomSeed() >= 0;
            case "outputTypes" -> spec.outputTypes() != null && !spec.outputTypes().isEmpty();
            default -> true;
        };
    }

    private EvaluationCaseResult result(EvaluationCase evalCase, boolean passed,
                                        String actualResult, String actualTool) {
        return new EvaluationCaseResult(evalCase.caseId(), evalCase.caseType(), evalCase.description(),
                evalCase.input(), evalCase.expectedResult(), evalCase.expectedTool(),
                evalCase.forbiddenTools(), evalCase.expectedStatus(), evalCase.expectedFields(),
                evalCase.tags(), passed, actualResult, actualTool,
                passed ? null : "expected " + evalCase.expectedResult() + " but got: " + actualResult);
    }
}
