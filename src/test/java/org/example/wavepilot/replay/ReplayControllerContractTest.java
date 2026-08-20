package org.example.wavepilot.replay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayControllerContractTest {
    @TempDir Path root;

    @Test
    void exposesAllRequiredReplayRoutes() {
        assertRoute(ReplayController.class, "start", PostMapping.class, "/experiments/{jobId}/replay");
        assertRoute(ReplayController.class, "list", GetMapping.class, "/replays");
        assertRoute(ReplayController.class, "get", GetMapping.class, "/replays/{replayId}");
        assertRoute(ReplayController.class, "comparison", GetMapping.class, "/replays/{replayId}/comparison");
        assertRoute(ReplayController.class, "manifest", GetMapping.class, "/replays/{replayId}/manifest");
    }

    @Test
    void controllerDataSourcesProduceCompleteReplayState() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        var source = ReplayTestSupport.createSucceededJob(stack);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("contract"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        assertEquals(ReplayStatus.SUCCEEDED, done.getStatus());
        assertEquals(done.getReplayId(), done.getManifest().replayId());
        assertEquals(done.getReplayId(), done.getComparison().replayId());
        assertEquals(source.getJobId(), done.getManifest().sourceJobId());
        assertEquals(source.getJobId(), done.getComparison().sourceJobId());
        assertEquals(done.getReplayJobId(), done.getComparison().replayJobId());
        assertNotNull(done.getManifest().replayFingerprint());
    }

    @Test
    void replayStateNeverExposesLocalAbsolutePaths() throws Exception {
        ReplayTestSupport.Stack stack = ReplayTestSupport.stack(root);
        var source = ReplayTestSupport.createSucceededJob(stack);
        ReplayRecord record = stack.replayService().startReplay(source.getJobId(), new ReplayRequest("paths"));
        ReplayRecord done = ReplayTestSupport.awaitReplayTerminal(stack, record.getReplayId());

        String json = stack.mapper().writeValueAsString(done);
        assertTrue(!json.contains(root.toAbsolutePath().toString().replace("\\", "/"))
                        && !json.contains("C:\\") && !json.contains("D:\\"),
                "replay JSON must not expose local file system paths");
    }

    private <A extends java.lang.annotation.Annotation> void assertRoute(
            Class<?> controller, String methodName, Class<A> annotationType, String route) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName)).findFirst().orElseThrow();
        A annotation = method.getAnnotation(annotationType);
        assertNotNull(annotation);
        String[] values = annotation instanceof GetMapping get ? get.value() : ((PostMapping) annotation).value();
        assertTrue(Arrays.asList(values).contains(route));
    }
}
