package org.example.wavepilot.experiment.validation;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.ValidationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parameter semantics of the polar-code K-identification type: power-of-two code lengths,
 * BSC error-rate sweep bounds, sample count and repetition count, MATLAB-compatible seed.
 * This is the sole owner of the polar semantics; other types get their own validator.
 */
public class PolarCodeKTypeValidator implements ExperimentTypeValidator {

    private static final long RESOURCE_RISK_THRESHOLD = 50_000_000L;
    private static final int MAX_ERROR_RATE_POINTS = 10_000;

    @Override
    public ExperimentType experimentType() {
        return ExperimentType.POLAR_CODE_K_IDENTIFICATION;
    }

    @Override
    public ValidationResult validate(ExperimentSpec spec) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (spec.codeLengths() == null || spec.codeLengths().isEmpty()) {
            errors.add("codeLengths must not be empty");
        } else {
            Set<Integer> uniqueCodeLengths = new HashSet<>();
            for (Integer codeLength : spec.codeLengths()) {
                if (codeLength == null || codeLength < 32) {
                    errors.add("Every codeLength must be at least 32");
                    continue;
                }
                if ((codeLength & (codeLength - 1)) != 0) {
                    errors.add("Every codeLength must be a power of two: " + codeLength);
                }
                if (codeLength > 512) {
                    errors.add("Every codeLength must be one of 32, 64, 128, 256 or 512: " + codeLength);
                }
                if (!uniqueCodeLengths.add(codeLength)) {
                    errors.add("codeLengths must not contain duplicates: " + codeLength);
                }
            }
        }
        if (!Double.isFinite(spec.errorRateStart()) || spec.errorRateStart() < 0) {
            errors.add("errorRateStart must be finite and >= 0");
        }
        if (!Double.isFinite(spec.errorRateEnd()) || spec.errorRateEnd() > 0.5) {
            errors.add("errorRateEnd must be finite and <= 0.5");
        }
        if (Double.isFinite(spec.errorRateStart()) && Double.isFinite(spec.errorRateEnd())
                && spec.errorRateStart() >= spec.errorRateEnd()) {
            errors.add("errorRateStart must be less than errorRateEnd");
        }
        if (!Double.isFinite(spec.errorRateStep()) || spec.errorRateStep() <= 0) {
            errors.add("errorRateStep must be finite and > 0");
        }
        if (spec.sampleCount() <= 0) {
            errors.add("sampleCount must be > 0");
        }
        if (spec.monteCarloTimes() <= 0) {
            errors.add("monteCarloTimes must be > 0");
        }
        if (spec.randomSeed() < 0) {
            errors.add("randomSeed must be >= 0");
        }
        if (spec.randomSeed() > 4_294_967_295L) {
            errors.add("randomSeed must be <= 4294967295 for MATLAB rng");
        }
        if (spec.outputTypes() == null || spec.outputTypes().isEmpty()) {
            errors.add("outputTypes must not be empty");
        }

        if (errors.isEmpty()) {
            int points = pointCount(spec);
            if (points > MAX_ERROR_RATE_POINTS) {
                errors.add("The error-rate range produces too many points: " + points);
            } else {
                long estimatedWork = saturatingMultiply(spec.codeLengths().size(), points,
                        spec.sampleCount(), spec.monteCarloTimes());
                if (estimatedWork >= RESOURCE_RISK_THRESHOLD) {
                    warnings.add("RESOURCE_RISK: estimated work units=" + estimatedWork
                            + "; review runtime and capacity before using a real runner");
                }
            }
        }

        return errors.isEmpty()
                ? ValidationResult.success(warnings)
                : ValidationResult.failure(errors, warnings);
    }

    @Override
    public int pointCount(ExperimentSpec spec) {
        BigDecimal start = BigDecimal.valueOf(spec.errorRateStart());
        BigDecimal end = BigDecimal.valueOf(spec.errorRateEnd());
        BigDecimal step = BigDecimal.valueOf(spec.errorRateStep());
        BigDecimal intervals = end.subtract(start).divide(step, 0, RoundingMode.FLOOR);
        if (intervals.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE - 1L)) > 0) {
            return Integer.MAX_VALUE;
        }
        return intervals.intValue() + 1;
    }

    private long saturatingMultiply(long... values) {
        long result = 1;
        for (long value : values) {
            if (value > 0 && result > Long.MAX_VALUE / value) {
                return Long.MAX_VALUE;
            }
            result *= value;
        }
        return result;
    }
}
