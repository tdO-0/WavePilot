package org.example.wavepilot.modelrouting;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Records why a model is or is not selected; it never invents token/cost data. */
@Component
public class DefaultModelRouter implements ModelRouter {
    private final boolean modelConfigured;
    private final String lowCostModel;
    private final String planningModel;

    public DefaultModelRouter(@Value("${spring.ai.dashscope.api-key:}") String apiKey,
                              @Value("${wavepilot.ai.chat-model:qwen3.7-max}") String planningModel) {
        this.modelConfigured = apiKey != null && !apiKey.isBlank() && !"not-configured".equals(apiKey);
        this.lowCostModel = "provider-low-cost-classifier";
        this.planningModel = planningModel;
    }

    @Override
    public ModelRoutingDecision route(ModelTaskType taskType, boolean semanticJudgmentRequired) {
        if (taskType == ModelTaskType.DETERMINISTIC_JAVA || !semanticJudgmentRequired) {
            return decision(taskType, "deterministic-java", false,
                    "deterministic validation/reporting is sufficient");
        }
        if (!modelConfigured) {
            return decision(taskType, "deterministic-fallback", false,
                    "no configured provider; offline fallback selected");
        }
        return switch (taskType) {
            case EXTRACTION_CLASSIFICATION -> decision(taskType, lowCostModel, true,
                    "bounded extraction/classification can use a lower-cost model");
            case PLANNING_REPLANNING -> decision(taskType, planningModel, true,
                    "complex planning may use the configured stronger model");
            case REPORT -> decision(taskType, planningModel, true,
                    "semantic polishing requested after deterministic grounded report generation");
            case DETERMINISTIC_JAVA -> decision(taskType, "deterministic-java", false,
                    "Java rule selected");
        };
    }

    private ModelRoutingDecision decision(ModelTaskType type, String route, boolean call, String reason) {
        return new ModelRoutingDecision(type, route, call, reason, null, null, Instant.now());
    }
}
