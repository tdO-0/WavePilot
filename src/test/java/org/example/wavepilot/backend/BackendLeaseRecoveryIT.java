package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Main;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.ArtifactType;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + RabbitMQ required; selected only by -Pbackend-it, never by default surefire.
 * Rows are crafted directly in MySQL to simulate workers that died mid-claim; the lease
 * recovery scanner must classify them conservatively and never double-run side effects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendLeaseRecoveryIT {
    @TempDir static Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<String> crafted = new ArrayList<>();
    private ConfigurableApplicationContext api, unsafeApi, worker, quietWorker;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startApiAndWorker() {
        api = start("api", "--wavepilot.lease.scan-interval-ms=1000", "--wavepilot.lease.scan-grace=0s");
        // The worker's own scanner stays disabled: the consumer still claims and executes
        // (claim does not check the lease switch), while classification is left to the
        // api-side scanners so the UNCERTAIN test below has no mock-safe scanner racing it.
        worker = start("worker", "--wavepilot.lease.enabled=false");
        jdbc = new JdbcTemplate(api.getBean(DataSource.class));
    }

    private ConfigurableApplicationContext start(String role, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--server.port=0", "--spring.jmx.enabled=false",
                "--wavepilot.node-role=" + role, "--wavepilot.job-repository=mysql",
                "--wavepilot.knowledge.repository=memory", "--wavepilot.embedding.offline=true",
                "--wavepilot.runner.type=mock", "--wavepilot.artifacts.shared-metadata=true",
                "--wavepilot.artifacts.root=" + temporary.resolve("artifacts"),
                "--wavepilot.scientific.run-store=" + temporary.resolve("runs-" + contexts.size()),
                "--wavepilot.scientific.execution-ledger-store=" + temporary.resolve("ledger-" + contexts.size()),
                "--spring.datasource.url=" + System.getProperty("backend.mysql.url",
                        "jdbc:mysql://localhost:3306/wavepilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true")));
        args.addAll(Arrays.asList(extra));
        var context = new SpringApplicationBuilder(Main.class).run(args.toArray(String[]::new));
        contexts.add(context);
        return context;
    }

    @AfterAll
    void closeAndCleanup() {
        // Clean up first, while the last-booted context's connection pool is still open.
        for (String jobId : crafted) {
            jdbc.update("DELETE FROM outbox_event WHERE job_id=?", jobId);
            jdbc.update("DELETE FROM experiment_job WHERE job_id=?", jobId);
        }
        for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).close();
    }

    /** Inserts a row exactly as a dead worker would have left it. */
    private void craft(ExperimentJob job, String externalJobId, String workerId,
                       long fencingToken, long version, boolean leaseExpired) {
        try {
            jdbc.update("INSERT INTO experiment_job (job_id, spec_json, plan_json, status, progress, " +
                            "external_job_id, worker_id, lease_until, heartbeat_at, attempt, fencing_token, " +
                            "version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    job.getJobId(),
                    json.writeValueAsString(job.getSpec()),
                    json.writeValueAsString(job.getPlan()),
                    job.getStatus().name(),
                    json.writeValueAsString(job.getProgress()),
                    externalJobId, workerId,
                    Timestamp.from(Instant.now().plusSeconds(leaseExpired ? -120 : 3600)),
                    Timestamp.from(Instant.now().minusSeconds(200)),
                    1, fencingToken, version,
                    Timestamp.from(job.getCreatedAt()), Timestamp.from(job.getUpdatedAt()));
            crafted.add(job.getJobId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test @Order(1)
    void expiredRunningJobBeforeSubmissionIsRequeuedAndExecuted() throws Exception {
        var job = WavePilotTestFixtures.job("JOB-LEASE-RQ-" + UUID.randomUUID());
        job.changeStatus(ExperimentStatus.RUNNING, "claimed by a dead worker");
        craft(job, null, "DEAD-WORKER-1", 1, 2, true);

        await(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, job.getJobId())));

        assertEquals(2, jdbc.queryForObject("SELECT attempt FROM experiment_job WHERE job_id=?",
                Integer.class, job.getJobId()));
        assertEquals(3L, jdbc.queryForObject("SELECT fencing_token FROM experiment_job WHERE job_id=?",
                Long.class, job.getJobId()));
        assertNotEquals("DEAD-WORKER-1", jdbc.queryForObject(
                "SELECT worker_id FROM experiment_job WHERE job_id=?", String.class, job.getJobId()));
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM outbox_event WHERE job_id=?",
                String.class, job.getJobId()));
    }

    @Test @Order(2)
    void expiredRunningJobAfterExternalSubmissionIsUncertainAndNeverRerun() throws Exception {
        // A runner with possible side effects: even a lease expiry must not re-execute it.
        api.close();
        unsafeApi = start("api", "--wavepilot.lease.scan-interval-ms=1000", "--wavepilot.lease.scan-grace=0s",
                "--wavepilot.lease.safe-requeue-runner-types=unsafe-none");
        quietWorker = start("worker", "--wavepilot.lease.enabled=false"); // its scanner must stay out of the way
        jdbc = new JdbcTemplate(unsafeApi.getBean(DataSource.class)); // the old api's pool is closed

        var submitted = WavePilotTestFixtures.job("JOB-LEASE-UC-" + UUID.randomUUID());
        submitted.changeStatus(ExperimentStatus.RUNNING, "claimed");
        craft(submitted, "MATLAB-EXTERNAL-1", "DEAD-WORKER-2", 2, 3, true);
        var validating = WavePilotTestFixtures.job("JOB-LEASE-UV-" + UUID.randomUUID());
        validating.changeStatus(ExperimentStatus.VALIDATING_RESULT, "validating");
        craft(validating, "MATLAB-EXTERNAL-2", "DEAD-WORKER-3", 3, 4, true);

        await(() -> "UNCERTAIN".equals(jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, submitted.getJobId())));
        await(() -> "UNCERTAIN".equals(jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, validating.getJobId())));
        Thread.sleep(2500); // several more scan cycles must not touch terminal rows
        assertEquals("UNCERTAIN", jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, submitted.getJobId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE job_id=?",
                Integer.class, submitted.getJobId()));
        assertTrue(jdbc.queryForObject("SELECT failure_reason FROM experiment_job WHERE job_id=?",
                String.class, submitted.getJobId()).contains("manual verification"));
    }

    @Test @Order(3)
    void validatingResultWithVerifiedArtifactsRebuildsSucceededWithoutExecution() throws Exception {
        var job = WavePilotTestFixtures.job("JOB-LEASE-RB-" + UUID.randomUUID());
        job.changeStatus(ExperimentStatus.VALIDATING_RESULT, "validating");
        job.updateProgress(90, "VALIDATING_RESULT", 6, 6, "runner finished");
        craft(job, "MOCK-EXTERNAL-3", "DEAD-WORKER-4", 4, 5, true);
        // The evidence the crashed worker left behind: registered + validated artifacts.
        var registry = unsafeApi.getBean(org.example.wavepilot.artifact.ArtifactRegistry.class);
        var artifact = registry.writeJson(job.getJobId(), ArtifactType.SUMMARY_JSON, "summary.json",
                Map.of("mock", true));
        registry.markJobValidated(job.getJobId(), "mock", true, false, "MOCK_RUNNER", "v1", "v1");
        assertTrue(registry.verify(artifact.artifactId()));

        await(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, job.getJobId())));
        assertEquals(100, jdbc.queryForObject(
                "SELECT CAST(JSON_EXTRACT(progress, '$.progress') AS UNSIGNED) FROM experiment_job WHERE job_id=?",
                Integer.class, job.getJobId()));
        // No outbox event: the rebuild re-used evidence, it did not re-run anything.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE job_id=?",
                Integer.class, job.getJobId()));
        assertEquals("DEAD-WORKER-4", jdbc.queryForObject(
                "SELECT worker_id FROM experiment_job WHERE job_id=?", String.class, job.getJobId()));
    }

    @Test @Order(4)
    void cancelledJobCannotBeOverwrittenByItsOldWorker() throws Exception {
        var job = WavePilotTestFixtures.job("JOB-LEASE-CL-" + UUID.randomUUID());
        job.changeStatus(ExperimentStatus.RUNNING, "claimed");
        // Lease far in the future: the scanners must leave this row alone.
        craft(job, "MOCK-EXTERNAL-4", "DEAD-WORKER-5", 5, 6, false);

        // The old worker wakes up with its pre-cancel in-memory snapshot and tries to write.
        var repository = unsafeApi.getBean(ExperimentJobRepository.class);
        var stale = repository.findById(job.getJobId()).orElseThrow(); // RUNNING snapshot
        var service = unsafeApi.getBean(ExperimentService.class);
        service.cancel(job.getJobId());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, job.getJobId()));
        assertThrows(OptimisticLockingFailureException.class,
                () -> repository.save(stale, ExperimentStatus.RUNNING));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM experiment_job WHERE job_id=?", String.class, job.getJobId()));
        // A redelivered message for the cancelled job is acknowledged as a duplicate.
        assertFalse(service.executeQueued(job.getJobId()));
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "condition did not complete before timeout");
    }
}
