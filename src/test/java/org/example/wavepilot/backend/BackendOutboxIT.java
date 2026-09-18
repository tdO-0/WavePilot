package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Main;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.messaging.BackendRabbitConfiguration;
import org.example.wavepilot.experiment.messaging.ExperimentMessagePublisher;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + RabbitMQ required; selected only by -Pbackend-it, never by default surefire.
 * Start the infrastructure services from docker-compose.backend.yml first. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendOutboxIT {
    @TempDir static Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private final List<String> createdJobs = new CopyOnWriteArrayList<>();
    private ConfigurableApplicationContext apiNoRelay, apiRelay1, apiRelay2, worker;
    private String base;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startApiWithoutRelay() {
        apiNoRelay = start("api", "--wavepilot.outbox.relay-enabled=false");
        base = "http://localhost:" + ((ServletWebServerApplicationContext) apiNoRelay).getWebServer().getPort();
        jdbc = new JdbcTemplate(apiNoRelay.getBean(DataSource.class));
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
        // Leave the shared dev database as the tests found it; clean up while the
        // connection pool backing `jdbc` is still open, then close the contexts.
        for (String jobId : createdJobs) {
            jdbc.update("DELETE FROM outbox_event WHERE job_id=?", jobId);
            jdbc.update("DELETE FROM experiment_job WHERE job_id=?", jobId);
        }
        for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).close();
    }

    private JsonNode create(String key) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/experiments"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(WavePilotTestFixtures.validSpec())))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        createdJobs.add(body.get("jobId").asText());
        return body;
    }

    @Test @Order(1)
    void createCommitsQueuedStatusAndOutboxEventTogether() throws Exception {
        JsonNode job = create("outbox-tx-commit-" + UUID.randomUUID());
        String jobId = job.get("jobId").asText();
        assertEquals("QUEUED", job.get("status").asText());
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM experiment_job WHERE job_id=?", String.class, jobId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE job_id=?", Integer.class, jobId));
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM outbox_event WHERE job_id=?", String.class, jobId));
        // Relay disabled: the request returned 202 without any RabbitMQ message.
        assertEquals(0, apiNoRelay.getBean(RabbitAdmin.class)
                .getQueueInfo(BackendRabbitConfiguration.QUEUE).getMessageCount());
    }

    @Test @Order(2)
    void rolledBackTransactionLeavesNeitherJobNorEvent() {
        var txManager = apiNoRelay.getBean(PlatformTransactionManager.class);
        var repository = apiNoRelay.getBean(ExperimentJobRepository.class);
        var job = WavePilotTestFixtures.job("JOB-ROLLBACK-" + UUID.randomUUID());
        job.changeStatus(ExperimentStatus.CREATED, "fixture");
        assertThrows(DataIntegrityViolationException.class, () -> new TransactionTemplate(txManager)
                .executeWithoutResult(status -> {
                    repository.save(job, ExperimentStatus.CREATED);
                    // Invalid JSON violates the outbox payload_json column and must roll
                    // the whole transaction back — the job write is not auto-committed anymore.
                    Instant now = Instant.now();
                    jdbc.update("INSERT INTO outbox_event (job_id, event_type, payload_json, status, " +
                                    "attempt_count, next_retry_at, created_at, version) " +
                                    "VALUES (?, 'EXPERIMENT_EXECUTE', 'not-json', 'PENDING', 0, ?, ?, 0)",
                            job.getJobId(), Timestamp.from(now), Timestamp.from(now));
                }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM experiment_job WHERE job_id=?",
                Integer.class, job.getJobId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE job_id=?",
                Integer.class, job.getJobId()));
        createdJobs.add(job.getJobId()); // cleanup no-op guard
    }

    @Test @Order(3)
    void relayStartedLaterContinuesUnsentEvents() throws Exception {
        JsonNode job = create("outbox-restart-" + UUID.randomUUID());
        String jobId = job.get("jobId").asText();
        // A second api instance boots with its relay enabled: it must pick up the pending
        // event left by the first (simulating a restart), and a worker must execute the job.
        apiRelay1 = start("api");
        worker = start("worker");
        await(() -> "SUCCEEDED".equals(jdbc.queryForObject("SELECT status FROM experiment_job WHERE job_id=?",
                String.class, jobId)));
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM outbox_event WHERE job_id=?",
                String.class, jobId));
        assertNotNull(jdbc.queryForObject("SELECT sent_at FROM outbox_event WHERE job_id=?", Timestamp.class, jobId));
    }

    @Test @Order(4)
    void twoRelayInstancesDoNotCorruptEventState() throws Exception {
        apiRelay2 = start("api"); // second relay-enabled api instance
        int total = 6;
        var executor = Executors.newFixedThreadPool(6);
        var jobIds = new ArrayList<String>();
        try {
            var futures = new ArrayList<Future<JsonNode>>();
            for (int i = 0; i < total; i++) {
                int index = i;
                futures.add(executor.submit(() -> create("outbox-dual-relay-" + UUID.randomUUID() + "-" + index)));
            }
            for (var future : futures) {
                String jobId = future.get(30, TimeUnit.SECONDS).get("jobId").asText();
                jobIds.add(jobId);
                await(() -> "SUCCEEDED".equals(jdbc.queryForObject(
                        "SELECT status FROM experiment_job WHERE job_id=?", String.class, jobId)));
            }
        } finally {
            executor.shutdownNow();
        }
        String placeholders = String.join(",", Collections.nCopies(jobIds.size(), "?"));
        assertEquals(total, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE job_id IN (" + placeholders + ") AND status='SENT'",
                Integer.class, jobIds.toArray()));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE job_id IN (" + placeholders + ") AND status='PENDING'",
                Integer.class, jobIds.toArray()));
    }

    @Test @Order(5)
    void sentEventsAreNotRepublishedAndDuplicatesNeverReexecute() throws Exception {
        int before = executions(worker);
        String jobId = createdJobs.get(createdJobs.size() - 1);
        // Deliberate duplicate publish (as if a relay re-published after a lost markSent):
        // the worker's DB claim must ACK the duplicates without resubmitting the Runner.
        var publisher = apiRelay1.getBean(ExperimentMessagePublisher.class);
        for (int i = 0; i < 4; i++) publisher.publish(jobId);
        await(() -> apiRelay1.getBean(RabbitAdmin.class)
                .getQueueInfo(BackendRabbitConfiguration.QUEUE).getMessageCount() == 0);
        Thread.sleep(2500); // several relay cycles (interval 1s): SENT rows must stay untouched
        assertEquals(before, executions(worker));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE job_id=? AND status='PENDING'",
                Integer.class, jobId));
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM outbox_event WHERE job_id=?",
                String.class, jobId));
    }

    @Test @Order(6)
    void actuatorPrometheusExposesWavepilotMetrics() throws Exception {
        var response = http.send(HttpRequest.newBuilder(
                URI.create(base + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        String body = response.body();
        for (String family : new String[] {"wavepilot_jobs_current", "wavepilot_jobs_completed_total",
                "wavepilot_jobs_created_total", "wavepilot_outbox_pending", "wavepilot_outbox_published_total",
                "wavepilot_outbox_publish_failures_total", "wavepilot_outbox_retries_total",
                "wavepilot_lease_expired_total", "wavepilot_lease_claims_total"}) {
            assertTrue(body.contains(family), "missing prometheus family: " + family);
        }
        assertTrue(body.contains("application=\"wavepilot\""), "application label missing");
        // The RabbitMQ consumer families live on the worker node, which owns the listener.
        String workerUrl = "http://localhost:"
                + ((ServletWebServerApplicationContext) worker).getWebServer().getPort();
        var workerMetrics = http.send(HttpRequest.newBuilder(
                URI.create(workerUrl + "/actuator/prometheus")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, workerMetrics.statusCode());
        for (String family : new String[] {"wavepilot_rabbitmq_consumed_total",
                "wavepilot_rabbitmq_duplicates_total", "wavepilot_rabbitmq_dlq_total"}) {
            assertTrue(workerMetrics.body().contains(family), "missing worker prometheus family: " + family);
        }
    }

    private int executions(ConfigurableApplicationContext context) {
        return ((Map<?, ?>) ReflectionTestUtils.getField(
                context.getBean(MockExperimentRunner.class), "executions")).size();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "condition did not complete before timeout");
    }
}
