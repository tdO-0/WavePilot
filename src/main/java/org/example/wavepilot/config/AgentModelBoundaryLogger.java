package org.example.wavepilot.config;

import org.example.wavepilot.intent.ExperimentIntentResolver;
import org.example.wavepilot.report.GroundedReportLanguageModel;
import org.example.wavepilot.report.ReportLanguageModel;
import org.example.wavepilot.template.generation.TemplateGenerationModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Startup log disclosing which model implementations are actually wired (Phase 21). A
 * full-mode deployment must never silently fall back to a stub: the log shows
 * IntentModel=..., TemplateGenerationModel=..., ReportLanguageModel=... so the operator can
 * see whether the real DashScope models are in use or a stub was selected by configuration.
 */
@Component
public class AgentModelBoundaryLogger implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AgentModelBoundaryLogger.class);

    private final ObjectProvider<ExperimentIntentResolver> intentResolver;
    private final ObjectProvider<TemplateGenerationModel> templateGeneration;
    private final ObjectProvider<ReportLanguageModel> reportModel;
    private final ObjectProvider<GroundedReportLanguageModel> groundedModel;

    public AgentModelBoundaryLogger(ObjectProvider<ExperimentIntentResolver> intentResolver,
                                    ObjectProvider<TemplateGenerationModel> templateGeneration,
                                    ObjectProvider<ReportLanguageModel> reportModel,
                                    ObjectProvider<GroundedReportLanguageModel> groundedModel) {
        this.intentResolver = intentResolver;
        this.templateGeneration = templateGeneration;
        this.reportModel = reportModel;
        this.groundedModel = groundedModel;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOG.info("Agent model boundary: IntentModel=ExperimentIntentResolver"
                + (intentResolver.getIfAvailable() == null ? " (offline fallback)" : " (real/offline resolver)"));
        TemplateGenerationModel generation = templateGeneration.getIfAvailable();
        LOG.info("Agent model boundary: TemplateGenerationModel={}",
                generation == null ? "NONE" : generation.name());
        ReportLanguageModel report = reportModel.getIfAvailable();
        LOG.info("Agent model boundary: ReportLanguageModel={}",
                report == null ? "NONE (deterministic template report)" : report.getClass().getSimpleName());
        GroundedReportLanguageModel grounded = groundedModel.getIfAvailable();
        LOG.info("Agent model boundary: GroundedReportLanguageModel={}",
                grounded == null ? "NONE (no grounded LLM analysis)" : grounded.name());
        if (generation == null || "stub-template-gen".equals(generation == null ? "" : generation.name())) {
            LOG.warn("Agent model boundary: template generation is running on the STUB model. "
                    + "Set wavepilot.template-generation.mode=llm for real LLM-designed templates.");
        }
    }
}
