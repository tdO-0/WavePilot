package org.example.wavepilot.template.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.experiment.model.OutputType;
import org.example.wavepilot.experiment.validation.DeclarativeResultContractValidator;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Real MATLAB candidate smoke runner. Active when explicitly configured
 * ({@code wavepilot.template.smoke.runner=local-matlab}) or when the experiment runner is
 * the real one ({@code wavepilot.runner.type=local-matlab}) — a full-mode deployment with
 * local MATLAB must smoke-test candidates against that same MATLAB, never fake it.
 * It copies ONLY the candidate template into an isolated temporary directory, runs a single
 * small grid point with a short timeout, validates the produced artifacts against the
 * declarative result contract and returns a SmokeResult. Smoke outputs are never used as
 * formal experiment results, and the runner never reads formal artifacts or touches other
 * templates.
 */
@Component
@ConditionalOnExpression(
        "'${wavepilot.template.smoke.runner:${wavepilot.runner.type:mock}}' == 'local-matlab'")
public class LocalMatlabCandidateSmokeRunner implements CandidateSmokeRunner {

    private final ExperimentDefinitionParser definitionParser;
    private final ObjectMapper objectMapper;
    private final String matlabExecutable;
    private final Duration timeout;
    private final ProcessLauncher processLauncher;

    @Autowired
    public LocalMatlabCandidateSmokeRunner(ExperimentDefinitionParser definitionParser,
                                           ObjectMapper objectMapper,
                                           @Value("${MATLAB_EXECUTABLE:matlab}") String matlabExecutable,
                                           @Value("${wavepilot.template.smoke.timeout:60s}") Duration timeout) {
        this(definitionParser, objectMapper, matlabExecutable, timeout, ProcessBuilder::start);
    }

    public LocalMatlabCandidateSmokeRunner(ExperimentDefinitionParser definitionParser,
                                           ObjectMapper objectMapper, String matlabExecutable,
                                           Duration timeout, ProcessLauncher processLauncher) {
        this.definitionParser = definitionParser;
        this.objectMapper = objectMapper;
        this.matlabExecutable = matlabExecutable;
        this.timeout = timeout;
        this.processLauncher = processLauncher;
    }

    @Override
    public SmokeResult run(TemplateCandidate candidate) {
        if (candidate.experimentTypeId() == null) {
            return new SmokeResult(true, false,
                    "Smoke 失败：候选没有 experimentTypeId，无法构造声明式小参数");
        }
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("wavepilot-smoke-" + safeId(candidate.candidateId()));
            writeCandidateFiles(candidate, workDir);
            ExperimentSpec spec = smallSpec(candidate);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(workDir.resolve("matlab-input.json").toFile(), spec);

            Process process = processLauncher.start(new ProcessBuilder(
                    matlabExecutable, "-sd", workDir.toString(),
                    "-batch", "run_experiment('matlab-input.json', '.')")
                    .directory(workDir.toFile())
                    .redirectErrorStream(true));
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SmokeResult(true, false,
                        "MATLAB Smoke 超时（" + timeout + "），已强制终止进程；"
                                + "请检查模板脚本是否存在死循环或过重逻辑。");
            }
            String output = readOutput(workDir.resolve("run.log"), process);
            if (process.exitValue() != 0) {
                return new SmokeResult(true, false,
                        "MATLAB Smoke 失败（exit " + process.exitValue() + "）：\n" + output);
            }
            List<String> errors = validateContract(candidate, spec, workDir);
            if (!errors.isEmpty()) {
                return new SmokeResult(true, false,
                        "MATLAB Smoke 产物未通过声明式契约：\n" + String.join("\n", errors)
                                + "\n--- 进程输出 ---\n" + output);
            }
            return new SmokeResult(true, true,
                    "MATLAB Smoke 通过：模板在隔离目录成功执行并产出符合契约的产物。\n"
                            + "执行时间：" + Instant.now() + "\n"
                            + "注意：Smoke 只证明模板可运行，不构成算法验证（algorithmValidated=false）。");
        } catch (Exception e) {
            return new SmokeResult(true, false, "MATLAB Smoke 执行异常：" + e.getMessage());
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void writeCandidateFiles(TemplateCandidate candidate, Path workDir) throws IOException {
        for (TemplateCandidate.CandidateFile file : candidate.files()) {
            String relative = file.relativePath();
            if (relative == null || relative.startsWith("/") || relative.contains("..")
                    || relative.contains("\\") || relative.matches("^[A-Za-z]:.*")) {
                throw new IOException("Smoke 拒绝不安全路径：" + relative);
            }
            // Same flattening as the formal runner: the -batch entry point must sit in the
            // job root while candidate packages keep MATLAB sources under matlab/.
            if (relative.startsWith("matlab/")) {
                relative = relative.substring("matlab/".length());
            }
            Path target = workDir.resolve(relative).normalize();
            if (!target.startsWith(workDir)) {
                throw new IOException("Smoke 文件逃逸工作目录：" + relative);
            }
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.content(), StandardCharsets.UTF_8);
        }
    }

    private ExperimentSpec smallSpec(TemplateCandidate candidate) {
        // The candidate carries its own definition even before publication; the smoke
        // never depends on the global registry being populated.
        ExperimentDefinition definition = definitionParser.parse(candidate.definitionYaml());
        Map<String, Object> parameters = new LinkedHashMap<>();
        for (ParameterDefinition parameter : definition.parameters()) {
            Object value = parameter.defaultValue();
            if (value == null) {
                value = smokeValue(parameter);
            }
            parameters.put(parameter.name(), value);
        }
        return new ExperimentSpec(ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                List.of(32), 0.0, 0.1, 0.05, 20, 10, 20L,
                List.of(OutputType.ACCURACY_CSV, OutputType.RUN_LOG),
                "smoke run for candidate " + candidate.candidateId(),
                candidate.experimentTypeId(), parameters);
    }

    private Object smokeValue(ParameterDefinition parameter) {
        return switch (parameter.type()) {
            case NUMBER, INTEGER -> {
                if (parameter.min() != null) yield parameter.min();
                yield parameter.max() != null ? parameter.max() : 1;
            }
            case BOOLEAN -> Boolean.TRUE;
            case ENUM -> parameter.enumValues().isEmpty() ? "default" : parameter.enumValues().get(0);
            case STRING -> "smoke";
        };
    }

    private List<String> validateContract(TemplateCandidate candidate, ExperimentSpec spec, Path workDir) {
        ExperimentDefinition definition = definitionParser.parse(candidate.definitionYaml());
        ExperimentPlan plan = new ExperimentPlan("PLAN-SMOKE", spec, candidate.templateId(), 1,
                List.of("RUN"), Instant.now());
        ExperimentJob job = new ExperimentJob("SMOKE-" + candidate.candidateId(), spec, plan);
        Map<ArtifactType, Path> byType = new EnumMap<>(ArtifactType.class);
        String[] names = {"accuracy.csv", "summary.json", "accuracy-curve.png", "run.log"};
        ArtifactType[] types = {ArtifactType.ACCURACY_CSV, ArtifactType.SUMMARY_JSON,
                ArtifactType.ACCURACY_CURVE, ArtifactType.RUN_LOG};
        for (int index = 0; index < names.length; index++) {
            Path file = workDir.resolve(names[index]);
            if (Files.isRegularFile(file)) {
                byType.put(types[index], file);
            }
        }
        List<String> errors = new ArrayList<>();
        new DeclarativeResultContractValidator(null, objectMapper)
                .validate(definition, job, byType, errors);
        return errors;
    }

    private String readOutput(Path logFile, Process process) throws IOException {
        if (Files.isRegularFile(logFile)) {
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            return content.length() > 2000 ? content.substring(0, 2000) : content;
        }
        try (InputStream input = process.getInputStream()) {
            byte[] bytes = input.readNBytes(2000);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private String safeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public interface ProcessLauncher {
        Process start(ProcessBuilder processBuilder) throws IOException;
    }
}
