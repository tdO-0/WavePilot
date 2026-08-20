package org.example.wavepilot.smoke;

import org.example.wavepilot.template.TemplateRecord;
import org.example.wavepilot.template.TemplateRegistry;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.publish.TemplatePublishingService;
import org.example.wavepilot.template.smoke.CandidateSmokeService;
import org.example.wavepilot.template.validation.CandidateValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real MATLAB candidate smoke, run only through the explicit `template-smoke` Maven profile:
 *   mvn -B -Ptemplate-smoke -DMATLAB_EXECUTABLE=... verify
 * Generates a demo candidate, validates it, runs a REAL MATLAB smoke (isolated directory,
 * small grid, short timeout) and requires SMOKE_PASSED plus operationalValidated=true on
 * publish. Without a real MATLAB environment this test fails loudly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("template-smoke")
class TemplateSmokeIT {

    @Autowired private TemplateGenerationService generation;
    @Autowired private CandidateValidationService validation;
    @Autowired private CandidateSmokeService smoke;
    @Autowired private TemplatePublishingService publishing;
    @Autowired private TemplateRegistry registry;

    @Test
    void realMatlabSmokePassesAndPublishesOperationallyValidated() {
        TemplateCandidate candidate = generation.generate("QPSK AWGN BER 模板");
        validation.validate(candidate.candidateId());
        TemplateCandidate afterSmoke = smoke.smoke(candidate.candidateId());

        assertEquals(TemplateCandidateStatus.SMOKE_PASSED, afterSmoke.status(),
                "a real MATLAB smoke must pass for the demo template");
        assertTrue(afterSmoke.realSmokeExecuted(), "smoke must have really executed");

        TemplateCandidate active = publishing.approveAndPublish(candidate.candidateId(), "smoke-it");
        assertEquals(TemplateCandidateStatus.ACTIVE, active.status());
        TemplateRecord record = registry.active(candidate.templateId()).orElseThrow();
        assertTrue(record.operationalValidated(),
                "published template must be operationalValidated after a real smoke");
        assertTrue(!record.algorithmValidated(),
                "smoke is not algorithm validation; algorithmValidated stays false");
    }
}
