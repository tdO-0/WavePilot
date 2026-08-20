package org.example.wavepilot.evaluation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The evaluation-time tool safety boundary. The controlled set mirrors the ten @Tool methods
 * of WavePilotAgentTools; the guard rejects any pick that is not controlled or is explicitly
 * forbidden, so an Agent can never reach process, file or repository handles through eval.
 */
@Component
public class EvaluationToolGuard {

    public static final Set<String> CONTROLLED_TOOLS = Set.of(
            "searchExperimentKnowledge",
            "createExperimentSpec",
            "validateExperimentSpec",
            "createExperimentPlan",
            "submitExperiment",
            "getExperimentStatus",
            "cancelExperiment",
            "listExperimentArtifacts",
            "readExperimentSummary",
            "compareExperiments",
            "listExperimentTemplates",
            "getExperimentTemplate",
            "listTemplateCandidates",
            "getTemplateCandidate",
            "generateTemplateCandidate",
            "validateTemplateCandidate",
            "requestTemplateSmoke");

    public Decision evaluate(String selectedTool, List<String> forbiddenTools) {
        List<String> forbidden = forbiddenTools == null ? List.of() : forbiddenTools;
        if (selectedTool == null || !CONTROLLED_TOOLS.contains(selectedTool)) {
            return new Decision(false,
                    "REJECTED: selected tool is not a controlled WavePilot tool: " + selectedTool);
        }
        if (forbidden.contains(selectedTool)) {
            return new Decision(false, "REJECTED: forbidden tool: " + selectedTool);
        }
        return new Decision(true, "ALLOWED: " + selectedTool);
    }

    public record Decision(boolean allowed, String message) { }
}
