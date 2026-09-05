package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.Main;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.messaging.*;
import org.example.wavepilot.experiment.model.*;
import org.example.wavepilot.experiment.repository.ExperimentJobRepository;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.example.wavepilot.runner.MockExperimentRunner;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL + RabbitMQ required; selected only by -Pbackend-it, never by default surefire.
 * Start the infrastructure services from docker-compose.backend.yml first. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BackendDistributedIT {
    @TempDir static Path temporary;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();
    private ConfigurableApplicationContext api, worker1, worker2;
    private String base, sequentialJob;
    private JdbcTemplate jdbc;

    @BeforeAll void startApi() {
        api = start("api");
        base = "http://localhost:" + ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        jdbc = new JdbcTemplate(api.getBean(DataSource.class));
    }

    private ConfigurableApplicationContext start(String role) {
        var context = new SpringApplicationBuilder(Main.class).run(
                "--server.port=0", "--spring.jmx.enabled=false",
                "--wavepilot.node-role=" + role, "--wavepilot.job-repository=mysql",
                "--wavepilot.knowledge.repository=memory", "--wavepilot.embedding.offline=true",
                "--wavepilot.runner.type=mock", "--wavepilot.artifacts.shared-metadata=true",
                "--wavepilot.artifacts.root=" + temporary.resolve("artifacts"),
                "--wavepilot.scientific.run-store=" + temporary.resolve("runs-" + contexts.size()),
                "--wavepilot.scientific.execution-ledger-store=" + temporary.resolve("ledger-" + contexts.size()),
                "--spring.datasource.url=" + System.getProperty("backend.mysql.url",
                    "jdbc:mysql://localhost:3306/wavepilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"));
        contexts.add(context);
        return context;
    }

    @AfterAll void close() {
        for (int i = contexts.size() - 1; i >= 0; i--) contexts.get(i).close();
    }

    private JsonNode create(String key) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/experiments"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(WavePilotTestFixtures.validSpec())))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    @Test @Order(1)
    void sequentialAndConcurrentHttpSubmissionUseOneDatabaseRowPerKey() throws Exception {
        String key = "it-sequential-" + UUID.randomUUID();
        sequentialJob = create(key).get("jobId").asText();
        assertEquals(sequentialJob, create(key).get("jobId").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM experiment_job WHERE idempotency_key=?", Integer.class, key));
        String concurrentKey = "it-concurrent-" + UUID.randomUUID();
        var executor = Executors.newFixedThreadPool(12);
        try {
            var gate = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(executor.submit(() -> { gate.await(); return create(concurrentKey).get("jobId").asText(); }));
            gate.countDown();
            Set<String> ids = new HashSet<>();
            for (var future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
            assertEquals(1, ids.size());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM experiment_job WHERE idempotency_key=?", Integer.class, concurrentKey));
        } finally { executor.shutdownNow(); }
        assertEquals("QUEUED", jdbc.queryForObject("SELECT status FROM experiment_job WHERE job_id=?", String.class, sequentialJob));
        assertEquals(0, executions(api)); // API never calls Mock Runner, even without any Worker.
    }

    @Test @Order(2)
    void actualWorkersConsumeApiMessageAndExposeResultThroughGetSseAndArtifacts() throws Exception {
        worker1 = start("worker"); worker2 = start("worker");
        await(() -> "SUCCEEDED".equals(jdbc.queryForObject("SELECT status FROM experiment_job WHERE job_id=?", String.class, sequentialJob)));
        var result = http.send(HttpRequest.newBuilder(URI.create(base + "/api/experiments/" + sequentialJob)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, result.statusCode());
        assertEquals("SUCCEEDED", json.readTree(result.body()).get("status").asText());
        var stream = http.send(HttpRequest.newBuilder(URI.create(base + "/api/experiments/" + sequentialJob + "/stream"))
                .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stream.statusCode()); assertTrue(stream.body().contains("event:progress")); assertTrue(stream.body().contains("SUCCEEDED"));
        var artifacts = api.getBean(ExperimentService.class).artifacts(sequentialJob);
        assertEquals(5, artifacts.size()); assertTrue(artifacts.stream().allMatch(a -> a.validated()));
        assertEquals(0, executions(api));
        String workerUrl = "http://localhost:" + ((ServletWebServerApplicationContext) worker1).getWebServer().getPort();
        var rejected = http.send(HttpRequest.newBuilder(URI.create(workerUrl + "/api/experiments"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(WavePilotTestFixtures.validSpec()))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(404, rejected.statusCode());
    }

    @Test @Order(3)
    void repeatedDeliveryNeverResubmitsCompletedJob() throws Exception {
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM experiment_job WHERE status='QUEUED' OR status='RUNNING'", Integer.class) == 0);
        int before = executions(worker1) + executions(worker2);
        String external = jdbc.queryForObject("SELECT external_job_id FROM experiment_job WHERE job_id=?", String.class, sequentialJob);
        var publisher = api.getBean(ExperimentMessagePublisher.class);
        for (int i = 0; i < 8; i++) publisher.publish(sequentialJob);
        await(() -> api.getBean(RabbitAdmin.class).getQueueInfo(BackendRabbitConfiguration.QUEUE).getMessageCount() == 0);
        Thread.sleep(500); // Allow both consumers to complete ACK, then inspect actual Runner submissions.
        assertEquals(before, executions(worker1) + executions(worker2));
        assertEquals(external, jdbc.queryForObject("SELECT external_job_id FROM experiment_job WHERE job_id=?", String.class, sequentialJob));
    }

    @Test @Order(4)
    void twoIndependentRepositoriesCompeteForOneQueuedRow() throws Exception {
        ExperimentJobRepository first = worker1.getBean(ExperimentJobRepository.class);
        ExperimentJobRepository second = worker2.getBean(ExperimentJobRepository.class);
        var job = WavePilotTestFixtures.job("JOB-IT-" + UUID.randomUUID());
        job.changeStatus(ExperimentStatus.QUEUED, "claim race fixture");
        first.insertIfAbsent(job);
        var left = first.findById(job.getJobId()).orElseThrow();
        var right = second.findById(job.getJobId()).orElseThrow();
        var pool = Executors.newFixedThreadPool(2); var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { gate.await(); return first.tryClaim(left); });
            var b = pool.submit(() -> { gate.await(); return second.tryClaim(right); });
            gate.countDown();
            assertEquals(1, (a.get() ? 1 : 0) + (b.get() ? 1 : 0));
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM experiment_job WHERE job_id=?", String.class, job.getJobId()));
            // A stale progress write must not overwrite the winner.
            assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                    () -> first.save(left.getVersion() == 0 ? left : right));
        } finally {
            pool.shutdownNow();
            jdbc.update("UPDATE experiment_job SET status='CANCELLED', version=version+1 WHERE job_id=?", job.getJobId());
        }
    }

    @Test @Order(5)
    void realBrokerRoutesExhaustedRetriesToDlqAndTransientRecoveryToAck() throws Exception {
        RabbitAdmin admin = api.getBean(RabbitAdmin.class);
        RabbitTemplate rabbit = api.getBean(RabbitTemplate.class);
        String suffix = UUID.randomUUID().toString();
        String queue = "wavepilot.it." + suffix, dead = queue + ".dead";
        admin.declareQueue(new org.springframework.amqp.core.Queue(dead, false));
        admin.declareQueue(QueueBuilder.nonDurable(queue).deadLetterExchange("").deadLetterRoutingKey(dead).build());
        ExperimentService failing = mock(ExperimentService.class);
        when(failing.executeQueued("JOB-RETRY-EXHAUSTED")).thenThrow(new TransientDataAccessResourceException("injected DB outage"));
        when(failing.executeQueued("JOB-RETRY-RECOVERED")).thenThrow(new TransientDataAccessResourceException("once")).thenReturn(true);
        ExperimentExecutionConsumer consumer = new ExperimentExecutionConsumer(failing, json);
        var listener = new SimpleMessageListenerContainer(api.getBean(ConnectionFactory.class));
        listener.setQueueNames(queue); listener.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        listener.setDefaultRequeueRejected(false); listener.setPrefetchCount(1);
        listener.setMessageListener((ChannelAwareMessageListener) consumer::consume);
        try {
            listener.start();
            rabbit.convertAndSend("", queue, ExperimentExecutionMessage.forJob("JOB-RETRY-EXHAUSTED"));
            Message rejected = rabbit.receive(dead, 10000);
            assertNotNull(rejected, "real DLQ must receive rejected delivery");
            assertTrue(rejected.getMessageProperties().getHeaders().containsKey("x-death"));
            verify(failing, times(4)).executeQueued("JOB-RETRY-EXHAUSTED");
            rabbit.convertAndSend("", queue, ExperimentExecutionMessage.forJob("JOB-RETRY-RECOVERED"));
            verify(failing, timeout(5000).times(2)).executeQueued("JOB-RETRY-RECOVERED");
            assertNull(rabbit.receive(dead, 1000));
        } finally { listener.stop(); admin.deleteQueue(queue); admin.deleteQueue(dead); }
    }

    private int executions(ConfigurableApplicationContext context) {
        return ((Map<?, ?>) ReflectionTestUtils.getField(context.getBean(MockExperimentRunner.class), "executions")).size();
    }

    @Test @Order(6)
    void replayKeepsSourceLinkAndReadsCrossWorkerArtifacts() throws Exception {
        var replays = api.getBean(org.example.wavepilot.replay.ReplayService.class);
        var replay = replays.startReplay(sequentialJob, null);
        await(() -> api.getBean(ExperimentService.class).get(replay.getReplayJobId()).getStatus().isTerminal());
        assertEquals(sequentialJob, jdbc.queryForObject("SELECT source_job_id FROM experiment_job WHERE job_id=?", String.class, replay.getReplayJobId()));
        await(() -> replays.get(replay.getReplayId()).getStatus().toString().equals("SUCCEEDED")
                || replays.get(replay.getReplayId()).getStatus().toString().equals("FAILED"));
        assertEquals("SUCCEEDED", replays.get(replay.getReplayId()).getStatus().toString(), replay.getFailureReason());
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "condition did not complete before timeout");
    }
}
