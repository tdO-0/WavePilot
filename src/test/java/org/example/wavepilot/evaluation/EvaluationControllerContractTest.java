package org.example.wavepilot.evaluation;

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

class EvaluationControllerContractTest {
    @TempDir Path root;

    @Test
    void exposesAllRequiredEvaluationRoutes() {
        assertRoute(EvaluationController.class, "run", PostMapping.class, "/evaluations/run");
        assertRoute(EvaluationController.class, "list", GetMapping.class, "/evaluations");
        assertRoute(EvaluationController.class, "get", GetMapping.class, "/evaluations/{evaluationId}");
        assertRoute(EvaluationController.class, "report", GetMapping.class, "/evaluations/{evaluationId}/report");
        assertRoute(EvaluationController.class, "compare", PostMapping.class, "/evaluations/compare");
    }

    @Test
    void controllerDataSourcesReturnCompleteRunState() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationRun run = stack.evaluationService().run("default", "stub-v1");

        assertEquals(run.evaluationId(), stack.evaluationService().get(run.evaluationId()).evaluationId());
        assertEquals(run.evaluationId(), stack.evaluationService().report(run.evaluationId()).evaluationId());
        assertEquals(11, stack.evaluationService().report(run.evaluationId()).metrics().size());
        assertEquals(1, stack.evaluationService().list().size());
        assertEquals("SUCCEEDED", stack.evaluationService().report(run.evaluationId()).status());
    }

    @Test
    void unknownModelNameIsRejectedWithoutExternalDependencies() {
        EvaluationTestSupport.Stack stack = EvaluationTestSupport.stack(root);
        EvaluationException exception = org.junit.jupiter.api.Assertions.assertThrows(
                EvaluationException.class, () -> stack.evaluationService().run("default", "external"));
        assertTrue(exception.getMessage().contains("external") || exception.getMessage().contains("stub"));
        assertEquals(0, stack.repository().findAll().size());
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
