package org.example.wavepilot.scientific.service;

import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.modelrouting.ModelRouter;
import org.example.wavepilot.modelrouting.ModelTaskType;
import org.example.wavepilot.scientific.model.ArtifactSnapshot;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.example.wavepilot.scientific.model.Observation;
import org.example.wavepilot.scientific.model.VerificationResult;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Component
public class ScientificVerifier {
    private static final Set<ArtifactType> REQUIRED = EnumSet.of(ArtifactType.EXPERIMENT_SPEC,
            ArtifactType.EXPERIMENT_PLAN, ArtifactType.ACCURACY_CSV, ArtifactType.SUMMARY_JSON,
            ArtifactType.RUN_LOG);

    private final ArtifactRegistry artifactRegistry;
    private final ModelRouter modelRouter;

    public ScientificVerifier(ArtifactRegistry artifactRegistry, ModelRouter modelRouter) {
        this.artifactRegistry = artifactRegistry;
        this.modelRouter = modelRouter;
    }

    public VerificationResult verify(ExperimentGoal goal, Observation observation,
                                     org.example.wavepilot.scientific.model.AgentRun run) {
        run.getTrace().recordRouting(modelRouter.route(ModelTaskType.DETERMINISTIC_JAVA, false));
        List<String> messages = new ArrayList<>();
        Set<ArtifactType> present = observation.artifacts().stream()
                .filter(ArtifactSnapshot::validated).map(ArtifactSnapshot::type)
                .collect(java.util.stream.Collectors.toSet());
        boolean complete = present.containsAll(REQUIRED);
        if (!complete) messages.add("Missing validated artifacts: " + REQUIRED.stream()
                .filter(type -> !present.contains(type)).toList());
        boolean hashesValid = observation.artifacts().stream().allMatch(this::verifySnapshot);
        if (!hashesValid) messages.add("One or more artifact hashes/sizes are invalid");
        Object raw = observation.metrics().get(goal.metricName());
        Double metric = raw instanceof Number number ? number.doubleValue() : null;
        boolean grounded = metric != null && observation.deterministicResultValidationPassed()
                && observation.artifacts().stream().anyMatch(snapshot -> snapshot.type() == ArtifactType.SUMMARY_JSON
                && snapshot.validated() && verifySnapshot(snapshot));
        if (!grounded) messages.add("Goal metric is not grounded in a validated summary artifact");
        boolean satisfied = metric != null && goal.operator().test(metric, goal.targetValue());
        messages.add(metric == null ? "Metric is absent: " + goal.metricName()
                : "Observed " + goal.metricName() + "=" + metric + ", target "
                + goal.operator() + " " + goal.targetValue());
        return new VerificationResult(observation.iteration(), complete && hashesValid && satisfied && grounded,
                complete && hashesValid, satisfied, grounded, metric, messages, Instant.now());
    }

    private boolean verifySnapshot(ArtifactSnapshot snapshot) {
        try {
            if (artifactRegistry.findById(snapshot.artifactId()).isPresent()) {
                return artifactRegistry.verify(snapshot.artifactId());
            }
            Path root = artifactRegistry.getRoot().toAbsolutePath().normalize();
            Path candidate = root.resolve(snapshot.relativePath()).normalize();
            if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(candidate) != snapshot.size()) return false;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(candidate)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest()).equals(snapshot.sha256());
        } catch (Exception e) {
            return false;
        }
    }
}
