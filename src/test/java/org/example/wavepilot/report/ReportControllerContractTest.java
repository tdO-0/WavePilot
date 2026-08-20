package org.example.wavepilot.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.artifact.ArtifactController;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportControllerContractTest {
    @TempDir Path root;

    @Test void exposesRequiredReportAndArtifactRoutesWithoutAbsolutePath() throws Exception {
        assertRoute(ReportController.class, "generate", PostMapping.class, "/experiments/{jobId}/report");
        assertRoute(ReportController.class, "get", GetMapping.class, "/experiments/{jobId}/report");
        assertRoute(ReportController.class, "data", GetMapping.class, "/experiments/{jobId}/report/data");
        assertRoute(ReportController.class, "validate", PostMapping.class, "/experiments/{jobId}/report/validate");
        assertRoute(ReportController.class, "citations", GetMapping.class, "/experiments/{jobId}/citations");
        assertRoute(ReportController.class, "citation", GetMapping.class, "/citations/{citationId}");
        assertRoute(ArtifactController.class, "metadata", GetMapping.class, "/{artifactId}/metadata");
        assertRoute(ArtifactController.class, "download", GetMapping.class, "/{artifactId}/download");
        assertRoute(ArtifactController.class, "verify", PostMapping.class, "/{artifactId}/verify");

        ReportTestSupport.Fixture fixture = ReportTestSupport.fixture(root);
        ArtifactRecord record = fixture.artifacts().get(0);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(record);
        assertFalse(json.contains(record.path()));
        assertTrue(json.contains("relativePath"));
        assertTrue(fixture.registry().verify(record.artifactId()));
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
