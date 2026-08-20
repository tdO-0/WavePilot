package org.example.wavepilot.experiment.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentPlan;
import org.example.wavepilot.experiment.model.ExperimentProgress;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Job repository persisted to {@code data/wavepilot/jobs/*.json} so experiment history
 * survives application restarts. Enabled with {@code wavepilot.jobs.persistence=file}
 * (in-memory stays the default so tests and minimal runs stay hermetic). The in-memory map
 * remains the single source of truth at runtime; every {@code save} writes the snapshot.
 */
@Repository
@ConditionalOnProperty(prefix = "wavepilot", name = "jobs.persistence", havingValue = "file")
public class FileSystemExperimentJobRepository implements ExperimentJobRepository {

    private final ConcurrentMap<String, ExperimentJob> jobs = new ConcurrentHashMap<>();
    private final Path directory;
    private final ObjectMapper objectMapper;

    public FileSystemExperimentJobRepository(
            @Value("${wavepilot.jobs.root:data/wavepilot/jobs}") String root,
            ObjectMapper objectMapper) throws IOException {
        this.directory = Path.of(root);
        this.objectMapper = objectMapper;
        Files.createDirectories(directory);
        loadExisting();
    }

    private void loadExisting() throws IOException {
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    Snapshot snapshot = objectMapper.readValue(path.toFile(), Snapshot.class);
                    jobs.put(snapshot.jobId(), snapshot.toJob());
                } catch (IOException e) {
                    throw new IOException("Cannot restore experiment job from " + path, e);
                }
            }
        }
    }

    @Override
    public ExperimentJob save(ExperimentJob job) {
        jobs.put(job.getJobId(), job);
        write(job);
        return job;
    }

    @Override
    public Optional<ExperimentJob> findById(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<ExperimentJob> findAll() {
        return jobs.values().stream()
                .sorted(Comparator.comparing(ExperimentJob::getCreatedAt).reversed())
                .toList();
    }

    private void write(ExperimentJob job) {
        try {
            Path target = directory.resolve(job.getJobId() + ".json");
            Files.write(target, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(Snapshot.from(job)));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist experiment job " + job.getJobId(), e);
        }
    }

    /** Serializable view of the job: the model class itself has no Jackson-friendly ctor. */
    private record Snapshot(String jobId, ExperimentSpec spec, ExperimentPlan plan,
                            Instant createdAt, Instant updatedAt, ExperimentStatus status,
                            ExperimentProgress progress, String externalJobId,
                            String sourceJobId, String failureReason) {

        static Snapshot from(ExperimentJob job) {
            return new Snapshot(job.getJobId(), job.getSpec(), job.getPlan(), job.getCreatedAt(),
                    job.getUpdatedAt(), job.getStatus(), job.getProgress(), job.getExternalJobId(),
                    job.getSourceJobId(), job.getFailureReason());
        }

        ExperimentJob toJob() {
            return new ExperimentJob(jobId, spec, plan, createdAt, updatedAt, status, progress,
                    externalJobId, sourceJobId, failureReason);
        }
    }
}
