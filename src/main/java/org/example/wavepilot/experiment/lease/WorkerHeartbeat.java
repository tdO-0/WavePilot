package org.example.wavepilot.experiment.lease;

import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Renews lease_until + heartbeat_at for every job this worker currently owns, without
 * bumping the optimistic-lock version (progress writes must never lose a race to the
 * heartbeat). Runs on the worker role with MySQL only; the per-poll progress save also
 * refreshes the lease, this scheduler covers gaps between polls (e.g. a slow validation
 * phase). A 0-row update means ownership was lost (recovered, cancelled or fenced) and
 * the next progress write will surface it as an optimistic-lock failure.
 */
@Component
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("'${wavepilot.node-role:standalone}' == 'worker' and '${wavepilot.job-repository:in-memory}' == 'mysql'")
public class WorkerHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(WorkerHeartbeat.class);

    private final ExperimentService experimentService;
    private final ExperimentJobRepository repository;
    private final LeaseProperties properties;

    public WorkerHeartbeat(ExperimentService experimentService, ExperimentJobRepository repository,
                           LeaseProperties properties) {
        this.experimentService = experimentService;
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${wavepilot.lease.heartbeat-interval-ms:15000}")
    public void beat() {
        if (!properties.enabled()) return;
        Instant now = Instant.now();
        for (ExperimentJob job : experimentService.activeLeasedJobs()) {
            if (job.getWorkerId() == null) continue;
            job.refreshLease(now, properties.duration());
            int updated = repository.updateHeartbeat(job.getJobId(), job.getWorkerId(),
                    job.getFencingToken(), job.getHeartbeatAt(), job.getLeaseUntil());
            if (updated != 1) log.warn("Heartbeat lost ownership of {} (fenced or recovered)", job.getJobId());
        }
    }
}
