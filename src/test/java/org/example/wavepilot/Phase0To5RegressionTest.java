package org.example.wavepilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One regression net across every phase: 0-2 core, 3 agent/RAG, 4 MATLAB, 4.5 baseline, 5A-5E. */
class Phase0To5RegressionTest {

    @Test
    void phase0To2CoreClassesSurvive() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.experiment.model.ExperimentSpec"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.model.ExperimentPlan"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.model.ExperimentJob"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.model.ExperimentStatus"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.validation.ExperimentSpecValidator"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.service.ExperimentStateMachine"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.service.ExperimentService"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.repository.InMemoryExperimentJobRepository"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.MockExperimentRunner"));
        assertNotNull(Class.forName("org.example.wavepilot.artifact.ArtifactRegistry"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.validation.ResultValidator"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.controller.ExperimentController"));
    }

    @Test
    void phase3AgentAndKnowledgeClassesSurvive() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.agent.WavePilotChatService"));
        assertNotNull(Class.forName("org.example.wavepilot.agent.WavePilotAgentTools"));
        assertNotNull(Class.forName("org.example.wavepilot.agent.WavePilotAgentPrompt"));
        assertNotNull(Class.forName("org.example.wavepilot.knowledge.KnowledgeService"));
        assertNotNull(Class.forName("org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository"));
        assertNotNull(Class.forName("org.example.wavepilot.knowledge.repository.MilvusWavePilotKnowledgeRepository"));
        assertNotNull(Class.forName("org.example.wavepilot.knowledge.repository.InMemoryWavePilotKnowledgeRepository"));
    }

    @Test
    void phase4And4_5RunnerBaselineClassesSurvive() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.runner.LocalMatlabExperimentRunner"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.MatlabTemplateCatalog"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.ProducedArtifact"));
        assertNotNull(Class.forName("org.example.wavepilot.runner.RunnerStatus"));
        assertNotNull(Class.forName("org.example.wavepilot.experiment.validation.RealPolarAlgorithmResultValidator"));
        assertTrue(org.example.wavepilot.runner.MatlabTemplateCatalog.require(
                "polar-k-identification-simple-v1").resourceFiles().size() > 5,
                "the simplified polar baseline template must remain intact");
    }

    @Test
    void phase5Ato5EClassesAndFrontendSurvive() throws Exception {
        assertNotNull(Class.forName("org.example.wavepilot.report.ReportService"));
        assertNotNull(Class.forName("org.example.wavepilot.replay.ReplayService"));
        assertNotNull(Class.forName("org.example.wavepilot.evaluation.EvaluationService"));
        assertNotNull(Class.forName("org.example.wavepilot.evaluation.ReferenceStubModel"));
        assertNotNull(Class.forName("org.example.wavepilot.evaluation.executor.ReplayConsistencyExecutor"));
        // The Agent-first workbench keeps every functional area reachable: agent (primary),
        // runs, template library, results/evidence, replay+eval quality page.
        String index = org.example.wavepilot.frontend.FrontendTestSupport.indexHtml();
        assertTrue(index.contains("WavePilot") && index.contains("通信仿真实验"),
                "the static workbench must remain in place");
        assertTrue(index.contains("data-page=\"agent\"") && index.contains("data-page=\"runs\"")
                        && index.contains("data-page=\"templates\"") && index.contains("data-page=\"results\"")
                        && index.contains("data-page=\"quality\""),
                "all first-level pages (agent/runs/templates/results/quality) must exist");
        assertTrue(index.contains("id=\"chatMessages\"") && index.contains("id=\"chatInput\"")
                        && index.contains("id=\"specJson\"") && index.contains("id=\"jobList\"")
                        && index.contains("id=\"artifactList\"") && index.contains("id=\"reportContent\"")
                        && index.contains("id=\"replayList\"") && index.contains("id=\"evalMetrics\"")
                        && index.contains("id=\"templateList\"") && index.contains("id=\"agentApprovalDrawer\""),
                "the workbench functional elements must remain in place");
    }
}
