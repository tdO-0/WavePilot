package org.example.wavepilot.experiment.repository;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Default (hermetic) repository; the file-backed one replaces it when persistence is on. */
@Repository
@ConditionalOnProperty(prefix = "wavepilot", name = "jobs.persistence", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryExperimentJobRepository implements ExperimentJobRepository {

    private final ConcurrentMap<String, ExperimentJob> jobs = new ConcurrentHashMap<>();

    @Override
    public ExperimentJob save(ExperimentJob job) {
        jobs.put(job.getJobId(), job);
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
}
