package org.example.wavepilot.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.artifact.*;
import org.example.wavepilot.experiment.messaging.ExperimentMessagePublisher;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.repository.*;
import org.example.wavepilot.experiment.service.*;
import org.example.wavepilot.experiment.validation.*;
import org.example.wavepilot.runner.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackendServiceTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private ExperimentService service(ExperimentJobRepository repository, ExperimentRunner runner, ArtifactRegistry registry) {
        var validator = new ExperimentSpecValidator();
        return new ExperimentService(validator, new ExperimentStateMachine(), repository, runner, registry,
                new ResultValidator(json, validator), json);
    }

    private void role(ExperimentService service, String role, ExperimentMessagePublisher publisher) {
        var beans = new StaticListableBeanFactory(Map.of("publisher", publisher));
        service.configureBackend(role, beans.getBeanProvider(ExperimentMessagePublisher.class));
    }

    @Test
    void repeatedAndConcurrentSubmissionsReturnOneJobAndApiNeverRunsIt() throws Exception {
        var repository = new InMemoryExperimentJobRepository();
        var runner = mock(ExperimentRunner.class);
        var publisher = mock(ExperimentMessagePublisher.class);
        var service = service(repository, runner, new ArtifactRegistry(temp.toString(), json));
        role(service, "api", publisher);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var calls = java.util.stream.IntStream.range(0, 16)
                    .<Callable<String>>mapToObj(i -> () -> service.create(WavePilotTestFixtures.validSpec(), "same").getJobId()).toList();
            var ids = executor.invokeAll(calls).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).distinct().toList();
            assertEquals(1, ids.size()); assertEquals(1, repository.findAll().size());
            assertEquals(ids.get(0), service.create(WavePilotTestFixtures.validSpec(), "same").getJobId());
            verify(runner, never()).submit(any());
        } finally { executor.shutdownNow(); service.shutdown(); }
    }

    @Test
    void publishFailureRetainsQueuedJobAndSameKeyCanRetryPublishing() {
        var repository = new InMemoryExperimentJobRepository();
        var runner = mock(ExperimentRunner.class); var publisher = mock(ExperimentMessagePublisher.class);
        var service = service(repository, runner, new ArtifactRegistry(temp.toString(), json));
        role(service, "api", publisher);
        doThrow(new IllegalStateException("broker unavailable")).doNothing().when(publisher).publish(anyString());
        try {
            assertThrows(ExperimentService.DispatchUnavailableException.class,
                    () -> service.create(WavePilotTestFixtures.validSpec(), "retry"));
            var first = repository.findAll().get(0);
            assertEquals(ExperimentStatus.QUEUED, first.getStatus());
            assertEquals(first.getJobId(), service.create(WavePilotTestFixtures.validSpec(), "retry").getJobId());
            assertEquals(1, repository.findAll().size());
        } finally { service.shutdown(); }
    }

    @Test
    void workerRejectsDirectCreation() {
        var service = service(new InMemoryExperimentJobRepository(), mock(ExperimentRunner.class), new ArtifactRegistry(temp.toString(), json));
        role(service, "worker", mock(ExperimentMessagePublisher.class));
        try { assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.create(WavePilotTestFixtures.validSpec())); }
        finally { service.shutdown(); }
    }

    @Test
    void runnerFailurePersistsFailedAndDuplicateCannotRunAgain() {
        var repository = new InMemoryExperimentJobRepository();
        var runner = mock(ExperimentRunner.class);
        when(runner.submit(any())).thenThrow(new IllegalStateException("Runner unavailable after claim"));
        var service = service(repository, runner, new ArtifactRegistry(temp.toString(), json));
        var job = WavePilotTestFixtures.job("JOB-FAIL"); job.changeStatus(ExperimentStatus.QUEUED, "ready");
        repository.insertIfAbsent(job);
        try {
            assertThrows(IllegalStateException.class, () -> service.executeQueued(job.getJobId()));
            assertEquals(ExperimentStatus.FAILED, repository.findById(job.getJobId()).orElseThrow().getStatus());
            assertTrue(job.getFailureReason().contains("Runner unavailable"));
            assertFalse(service.executeQueued(job.getJobId()));
            verify(runner).submit(any());
        } finally { service.shutdown(); }
    }

    @Test
    void defaultAndFileSelectionDoNotCreateExternalInfrastructure() {
        var context = new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withBean(ObjectMapper.class, () -> json)
                .withUserConfiguration(InMemoryExperimentJobRepository.class, FileSystemExperimentJobRepository.class,
                        org.example.wavepilot.experiment.repository.mysql.MySqlJobConfiguration.class,
                        org.example.wavepilot.experiment.messaging.BackendRabbitConfiguration.class);
        context.run(app -> {
            assertInstanceOf(InMemoryExperimentJobRepository.class, app.getBean(ExperimentJobRepository.class));
            assertEquals(0, app.getBeansOfType(javax.sql.DataSource.class).size());
            assertEquals(0, app.getBeansOfType(org.springframework.amqp.rabbit.connection.ConnectionFactory.class).size());
        });
        context.withPropertyValues("wavepilot.job-repository=file", "wavepilot.jobs.root=" + temp.resolve("file"))
                .run(app -> assertInstanceOf(FileSystemExperimentJobRepository.class, app.getBean(ExperimentJobRepository.class)));
    }

    @Test
    void fileRepositoryKeepsIdempotencyAcrossRestart() throws Exception {
        var first = new FileSystemExperimentJobRepository(temp.toString(), json);
        var job = WavePilotTestFixtures.job("JOB-FILEKEY"); job.setIdempotencyKey("file-key");
        first.insertIfAbsent(job);
        var second = new FileSystemExperimentJobRepository(temp.toString(), json);
        var duplicate = WavePilotTestFixtures.job("JOB-OTHER"); duplicate.setIdempotencyKey("file-key");
        assertEquals(job.getJobId(), second.insertIfAbsent(duplicate).getJobId());
        assertEquals(1, second.findAll().size());
    }

    @Test
    void apiCanDiscoverWorkerValidatedArtifactsAcrossRegistries() {
        var api = new ArtifactRegistry(temp.toString(), json);
        var worker = new ArtifactRegistry(temp.toString(), json);
        ReflectionTestUtils.setField(api, "sharedMetadata", true);
        ReflectionTestUtils.setField(worker, "sharedMetadata", true);
        api.writeJson("JOB-SHARED", ArtifactType.EXPERIMENT_SPEC, "spec.json", Map.of("value", 1));
        var result = worker.writeJson("JOB-SHARED", ArtifactType.SUMMARY_JSON, "summary.json", Map.of("mock", true));
        worker.markJobValidated("JOB-SHARED", "mock", true, false, "MOCK_RUNNER", "v1", "v1");
        assertEquals(2, api.listByJobId("JOB-SHARED").size());
        assertTrue(api.findById(result.artifactId()).orElseThrow().validated());
        assertTrue(api.verify(result.artifactId()));
    }
}
