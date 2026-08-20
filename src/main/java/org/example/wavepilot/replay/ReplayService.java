package org.example.wavepilot.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.artifact.ArtifactRegistry;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.evaluation.ReplayFingerprintInput;
import org.example.wavepilot.evaluation.ReplayFingerprintService;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Replay orchestration: validates the source job and its artifacts, computes a deterministic
 * Replay Fingerprint from the canonical spec and key configuration, creates an independent
 * replay job with the same randomSeed and template, compares structured results and registers
 * REPLAY_MANIFEST and REPLAY_COMPARISON artifacts in the replay job's own directory.
 */
@Service
public class ReplayService {

    private static final long POLL_INTERVAL_MILLIS = 50;
    private static final long MAX_WAIT_MILLIS = 30 * 60 * 1000L;
    private static final Set<ArtifactType> KEY_TYPES = EnumSet.of(
            ArtifactType.EXPERIMENT_SPEC, ArtifactType.EXPERIMENT_PLAN,
            ArtifactType.ACCURACY_CSV, ArtifactType.SUMMARY_JSON);

    private final ExperimentService experimentService;
    private final ArtifactRegistry artifactRegistry;
    private final ReplayFingerprintService fingerprintService;
    private final ReplayComparisonEvaluator comparisonEvaluator;
    private final MatlabTemplateDigest templateDigest;
    private final ReplayRepository repository;
    private final ObjectMapper objectMapper;
    private final double numericTolerance;
    private final ExecutorService replayExecutor = Executors.newSingleThreadExecutor();

    public ReplayService(ExperimentService experimentService, ArtifactRegistry artifactRegistry,
                         ReplayFingerprintService fingerprintService,
                         ReplayComparisonEvaluator comparisonEvaluator,
                         MatlabTemplateDigest templateDigest, ReplayRepository repository,
                         ObjectMapper objectMapper,
                         @Value("${wavepilot.replay.numeric-tolerance:1.0e-9}") double numericTolerance) {
        this.experimentService = experimentService;
        this.artifactRegistry = artifactRegistry;
        this.fingerprintService = fingerprintService;
        this.comparisonEvaluator = comparisonEvaluator;
        this.templateDigest = templateDigest;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.numericTolerance = numericTolerance;
    }

    public ReplayRecord startReplay(String sourceJobId, ReplayRequest request) {
        ExperimentJob source = requireReplayableSource(sourceJobId);
        ArtifactRecord csv = keyArtifact(sourceJobId, ArtifactType.ACCURACY_CSV);
        Map<String, Object> summary = readSummary(sourceJobId);
        String templateVersion = source.getPlan().experimentTemplateVersion();
        String matlabTemplateSha256 = templateDigest.compute(templateVersion);
        boolean mock = csv.mock();
        String classification = csv.classification();

        Object specForReplay = source.getGenericSpec() != null ? source.getGenericSpec() : source.getSpec();
        long randomSeed = source.getGenericSpec() != null
                ? (source.getGenericSpec().randomSeed() == null ? 20L : source.getGenericSpec().randomSeed())
                : source.getSpec().randomSeed();
        ReplayFingerprintInput fingerprintInput = new ReplayFingerprintInput(
                specForReplay, templateVersion, randomSeed, matlabTemplateSha256,
                Map.of("runnerType", csv.runnerType(),
                        "algorithmName", text(summary, "algorithmName", "unknown"),
                        "algorithmVersion", csv.algorithmVersion(),
                        "classification", classification),
                keyArtifacts(sourceJobId).stream().map(ArtifactRecord::sha256).toList());
        String fingerprint = fingerprintService.fingerprint(fingerprintInput);

        String replayId = "REPLAY-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String experimentTypeName = source.getGenericSpec() != null
                ? source.getGenericSpec().experimentTypeId()
                : source.getSpec().experimentType().name();
        ReplayManifest manifest = new ReplayManifest(replayId, sourceJobId, null,
                experimentTypeName,
                fingerprintService.canonicalJson(specForReplay),
                randomSeed, csv.runnerType(), templateVersion,
                text(summary, "algorithmName", "unknown"), csv.algorithmVersion(),
                classification, mock, csv.algorithmValidated(),
                matlabTemplateSha256, javaApplicationVersion(), fingerprint, Instant.now());

        ReplayRecord record = new ReplayRecord(replayId, sourceJobId,
                request == null ? "" : request.note());
        record.start(manifest);
        repository.save(record);

        ExperimentJob replayJob = source.getGenericSpec() != null
                ? experimentService.create(source.getGenericSpec())
                : experimentService.create(source.getSpec());
        replayJob.setSourceJobId(sourceJobId);
        record.setReplayJobId(replayJob.getJobId());
        record.updateManifest(manifest.withReplayJobId(replayJob.getJobId()));
        repository.save(record);

        replayExecutor.submit(() -> finalizeReplay(record));
        return record;
    }

    public ReplayRecord get(String replayId) {
        return repository.findById(replayId)
                .orElseThrow(() -> new NoSuchElementException("Replay not found: " + replayId));
    }

    public List<ReplayRecord> list() { return repository.findAll(); }

    public ReplayManifest manifest(String replayId) {
        ReplayManifest manifest = get(replayId).getManifest();
        if (manifest == null) {
            throw new NoSuchElementException("Replay manifest is unavailable: " + replayId);
        }
        return manifest;
    }

    public ReplayComparisonResult comparison(String replayId) {
        ReplayComparisonResult comparison = get(replayId).getComparison();
        if (comparison == null) {
            throw new NoSuchElementException("Replay comparison is not ready: " + replayId);
        }
        return comparison;
    }

    private void finalizeReplay(ReplayRecord record) {
        try {
            String replayJobId = record.getReplayJobId();
            ExperimentStatus outcome = awaitTerminal(replayJobId);
            if (outcome != ExperimentStatus.SUCCEEDED) {
                record.fail("Replay job " + replayJobId + " ended with " + outcome);
                return;
            }
            ExperimentJob source = experimentService.get(record.getSourceJobId());
            ExperimentJob replay = experimentService.get(replayJobId);
            ReplayComparisonResult comparison = comparisonEvaluator.evaluate(
                    source, replay, numericTolerance, record.getReplayId());
            ReplayManifest manifest = record.getManifest();
            artifactRegistry.writeJson(replayJobId, ArtifactType.REPLAY_MANIFEST,
                    "replay-manifest.json", manifest);
            artifactRegistry.writeJson(replayJobId, ArtifactType.REPLAY_COMPARISON,
                    "replay-comparison.json", comparison);
            ArtifactRecord csv = keyArtifact(replayJobId, ArtifactType.ACCURACY_CSV);
            artifactRegistry.markJobValidated(replayJobId, csv.runnerType(), csv.mock(),
                    csv.algorithmValidated(), csv.classification(), csv.templateVersion(),
                    csv.algorithmVersion());
            record.complete(manifest, comparison);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            record.fail("Replay finalization interrupted");
        } catch (RuntimeException e) {
            record.fail("Replay finalization failed: " + e.getMessage());
        } finally {
            repository.save(record);
        }
    }

    private ExperimentStatus awaitTerminal(String replayJobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ExperimentStatus status = experimentService.progress(replayJobId).status();
            if (status.isTerminal()) return status;
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        return ExperimentStatus.FAILED;
    }

    private ExperimentJob requireReplayableSource(String sourceJobId) {
        ExperimentJob job = experimentService.get(sourceJobId);
        if (job.getStatus() != ExperimentStatus.SUCCEEDED) {
            throw new ReplayValidationException("Source job must be SUCCEEDED before replay: " + sourceJobId);
        }
        List<ArtifactRecord> artifacts = artifactRegistry.listByJobId(sourceJobId);
        long keyCount = artifacts.stream().filter(record -> KEY_TYPES.contains(record.type())).count();
        if (keyCount != KEY_TYPES.size()) {
            throw new ReplayValidationException("Source job is missing key artifacts for replay: " + sourceJobId);
        }
        if (artifacts.stream().anyMatch(record -> !record.validated())) {
            throw new ReplayValidationException("Source artifacts did not all pass ResultValidator: " + sourceJobId);
        }
        for (ArtifactRecord record : artifacts) {
            if (!artifactRegistry.verify(record.artifactId())) {
                throw new ReplayValidationException("Source artifact hash or size changed: " + record.artifactId());
            }
        }
        return job;
    }

    private ArtifactRecord keyArtifact(String jobId, ArtifactType type) {
        return artifactRegistry.listByJobId(jobId).stream()
                .filter(record -> record.type() == type)
                .findFirst()
                .orElseThrow(() -> new ReplayValidationException(
                        "Required artifact is missing for " + jobId + ": " + type));
    }

    private List<ArtifactRecord> keyArtifacts(String jobId) {
        return artifactRegistry.listByJobId(jobId).stream()
                .filter(record -> KEY_TYPES.contains(record.type()))
                .toList();
    }

    private Map<String, Object> readSummary(String jobId) {
        ArtifactRecord summary = keyArtifact(jobId, ArtifactType.SUMMARY_JSON);
        try {
            return objectMapper.readValue(artifactRegistry.resolveVerified(summary.artifactId()).toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new ReplayValidationException("Cannot read source summary.json: " + jobId, e);
        }
    }

    private String text(Map<String, Object> values, String field, String fallback) {
        Object value = values.get(field);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String javaApplicationVersion() {
        String version = ReplayService.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "unknown" : version;
    }

    @PreDestroy
    public void shutdown() { replayExecutor.shutdownNow(); }

    public static class ReplayValidationException extends RuntimeException {
        public ReplayValidationException(String message) { super(message); }
        public ReplayValidationException(String message, Throwable cause) { super(message, cause); }
    }
}
