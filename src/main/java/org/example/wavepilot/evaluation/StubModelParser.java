package org.example.wavepilot.evaluation;

import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic scripted parser shared by the offline stub models. It extracts the same
 * fields a real LLM extraction would: code lengths, BSC error-rate range, step, sample
 * count, repetitions and seed. Results are a pure function of the input text, so offline
 * evaluation runs are reproducible.
 */
final class StubModelParser {

    private static final Pattern CODE_LENGTHS = Pattern.compile("码长\\s*([0-9,\\s和、]+)");
    private static final Pattern RATE_RANGE = Pattern.compile("错误率\\s*([0-9.]+)\\s*到\\s*([0-9.]+)");
    private static final Pattern RATE_STEP = Pattern.compile("步长\\s*([0-9.]+)");
    private static final Pattern SAMPLE_COUNT = Pattern.compile("M\\s*=\\s*(\\d+)|(\\d+)\\s*码字");
    private static final Pattern MONTE_CARLO = Pattern.compile("T\\s*=\\s*(\\d+)|(\\d+)\\s*次重复");
    private static final Pattern SEED = Pattern.compile("种子\\s*(\\d+)");

    private static final Set<Integer> SUPPORTED_CODE_LENGTHS = Set.of(32, 64, 128, 256, 512);

    private StubModelParser() { }

    static ExperimentSpec parseSpec(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Evaluation case input must not be blank");
        }
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                codeLengths(input), rate(input, RATE_RANGE, 0, 0), rate(input, RATE_RANGE, 1, 0.1),
                rate(input, RATE_STEP, 0, 0.01), Math.toIntExact(number(input, SAMPLE_COUNT, 0)),
                Math.toIntExact(number(input, MONTE_CARLO, 0)), number(input, SEED, 0),
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG),
                input.length() > 120 ? input.substring(0, 120) : input);
    }

    static List<String> missingParameters(String input) {
        List<String> missing = new ArrayList<>();
        if (!CODE_LENGTHS.matcher(input).find()) missing.add("codeLengths");
        if (!RATE_RANGE.matcher(input).find()) missing.add("errorRateStart");
        if (!RATE_RANGE.matcher(input).find()) missing.add("errorRateEnd");
        if (!RATE_STEP.matcher(input).find()) missing.add("errorRateStep");
        if (!SAMPLE_COUNT.matcher(input).find()) missing.add("sampleCount");
        if (!MONTE_CARLO.matcher(input).find()) missing.add("monteCarloTimes");
        if (!SEED.matcher(input).find()) missing.add("randomSeed");
        return missing;
    }

    static String pickTool(String input) {
        if (input.contains("ProcessBuilder") || input.contains("直接运行") || input.contains("matlab 脚本")) {
            return "ProcessBuilder";
        }
        if (input.contains("创建") || input.contains("提交")) return "submitExperiment";
        if (input.contains("状态") || input.contains("进度")) return "getExperimentStatus";
        if (input.contains("取消")) return "cancelExperiment";
        if (input.contains("检索") || input.contains("知识")) return "searchExperimentKnowledge";
        return "createExperimentSpec";
    }

    private static List<Integer> codeLengths(String input) {
        Matcher matcher = CODE_LENGTHS.matcher(input);
        List<Integer> result = new ArrayList<>();
        if (matcher.find()) {
            String[] tokens = matcher.group(1).split("[,\\s和、]+");
            for (String token : tokens) {
                if (token.isBlank()) continue;
                try {
                    int value = Integer.parseInt(token.trim());
                    if (SUPPORTED_CODE_LENGTHS.contains(value)) result.add(value);
                } catch (NumberFormatException ignored) {
                    // 48 is intentionally unsupported; the validator must reject it.
                }
            }
        }
        return result;
    }

    private static double rate(String input, Pattern pattern, int group, double fallback) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            try {
                return Double.parseDouble(matcher.group(group + 1));
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return fallback;
    }

    private static long number(String input, Pattern pattern, long fallback) {
        Matcher matcher = pattern.matcher(input);
        if (matcher.find()) {
            String group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (group != null) {
                try {
                    return Long.parseLong(group);
                } catch (NumberFormatException ignored) {
                    // fall through to the default
                }
            }
        }
        return fallback;
    }
}
