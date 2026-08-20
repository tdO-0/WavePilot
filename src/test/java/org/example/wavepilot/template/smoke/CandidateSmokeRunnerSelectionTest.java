package org.example.wavepilot.template.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke-runner selection must follow the deployment: a full mode with real MATLAB
 * (wavepilot.runner.type=local-matlab) automatically uses the real MATLAB candidate smoke
 * runner; only an explicit fake setting (or a mock runner) keeps the fake. This is enforced
 * at the bean-condition level, which these assertions lock down.
 */
class CandidateSmokeRunnerSelectionTest {

    @Test
    void localMatlabExperimentRunnerActivatesTheRealSmokeRunner() {
        assertEquals("'${wavepilot.template.smoke.runner:${wavepilot.runner.type:mock}}' == 'local-matlab'",
                conditionalExpression(LocalMatlabCandidateSmokeRunner.class),
                "the real smoke runner must follow the experiment runner type");
    }

    @Test
    void fakeSmokeRunnerIsTheComplement() {
        assertEquals("'${wavepilot.template.smoke.runner:${wavepilot.runner.type:mock}}' != 'local-matlab'",
                conditionalExpression(FakeCandidateSmokeRunner.class),
                "the fake runner must be the exact complement");
    }

    @Test
    void fakeRunnerReportsExecutedFalseWithoutFakingPassed() {
        FakeCandidateSmokeRunner runner = new FakeCandidateSmokeRunner();
        CandidateSmokeRunner.SmokeResult result = runner.run(null);
        assertTrue(!result.executed() && !result.passed(),
                "the fake runner must never report executed/passed");
        assertTrue(result.report().contains("MATLAB Smoke 未执行"),
                "the report must disclose that no real smoke ran");
    }

    private String conditionalExpression(Class<?> type) {
        Conditional conditional = AnnotationUtils.findAnnotation(type, Conditional.class);
        assertNotNull(conditional, type.getSimpleName() + " must be conditionally registered");
        boolean hasExpression = false;
        for (Class<? extends org.springframework.context.annotation.Condition> condition : conditional.value()) {
            if (condition.getName().contains("OnExpression")) hasExpression = true;
        }
        assertTrue(hasExpression, "expected an @ConditionalOnExpression on " + type.getSimpleName());
        return type.getAnnotation(ConditionalOnExpression.class).value();
    }
}
