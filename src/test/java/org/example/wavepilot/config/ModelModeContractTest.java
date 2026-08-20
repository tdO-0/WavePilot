package org.example.wavepilot.config;

import org.example.wavepilot.report.DashScopeReportLanguageModel;
import org.example.wavepilot.report.GroundedReportLanguageModel;
import org.example.wavepilot.template.generation.DashScopeTemplateGenerationModel;
import org.example.wavepilot.template.generation.StubTemplateGenerationModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.AnnotationUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 21 profile boundary: the LLM implementations are wired only in llm mode, the stub
 * only in stub/offline mode. A production misconfiguration must not silently fall back to
 * the stub — the conditional annotations make the boundary explicit and the startup logger
 * discloses the active implementations.
 */
class ModelModeContractTest {

    private String propertyValue(Class<?> type, String prefix, String name) {
        Conditional conditional = AnnotationUtils.findAnnotation(type, Conditional.class);
        assertTrue(conditional != null, type.getSimpleName() + " must be conditionally registered");
        ConditionalOnProperty onProperty = type.getAnnotation(ConditionalOnProperty.class);
        if (onProperty != null) {
            for (String n : onProperty.name()) {
                if (onProperty.prefix().equals(prefix) && n.equals(name)) {
                    return onProperty.havingValue();
                }
            }
        }
        throw new AssertionError("expected @ConditionalOnProperty(" + prefix + "." + name + ") on "
                + type.getSimpleName());
    }

    @Test
    void llmTemplateGenerationOnlyInLlmMode() {
        assertEquals("llm", propertyValue(DashScopeTemplateGenerationModel.class,
                "wavepilot", "template-generation.mode"),
                "the LLM template generator must be gated on mode=llm");
    }

    @Test
    void stubTemplateGenerationOnlyInStubMode() {
        assertEquals("stub", propertyValue(StubTemplateGenerationModel.class,
                "wavepilot", "template-generation.mode"),
                "the stub generator must be gated on mode=stub");
    }

    @Test
    void groundedReportModelOnlyInLlmMode() {
        assertEquals("llm", propertyValue(DashScopeReportLanguageModel.class,
                "wavepilot", "report-language.mode"),
                "the grounded LLM report model must be gated on mode=llm");
    }

    @Test
    void groundedModelNameIsDisclosed() {
        // The startup logger prints the concrete implementation name so an operator can
        // verify the real model is wired and never a silent stub fallback.
        assertEquals("dashscope-report-language", DashScopeReportLanguageModel.NAME);
        assertEquals("dashscope-template-gen", DashScopeTemplateGenerationModel.NAME);
        assertEquals("stub-template-gen", StubTemplateGenerationModel.NAME);
    }
}
