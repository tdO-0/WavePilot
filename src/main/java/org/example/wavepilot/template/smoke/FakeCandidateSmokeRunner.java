package org.example.wavepilot.template.smoke;

import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Fallback smoke runner: active when smoke is explicitly set to fake or when the experiment
 * runner is not the real MATLAB one (mock runs stay hermetic). No smoke is executed and the
 * candidate can never claim SMOKE_PASSED: it reports executed=false and the service moves
 * the candidate to REVIEW_REQUIRED with the report stating "MATLAB Smoke 未执行".
 */
@Component
@ConditionalOnExpression(
        "'${wavepilot.template.smoke.runner:${wavepilot.runner.type:mock}}' != 'local-matlab'")
public class FakeCandidateSmokeRunner implements CandidateSmokeRunner {

    @Override
    public SmokeResult run(TemplateCandidate candidate) {
        return new SmokeResult(false, false,
                "MATLAB Smoke 未执行：当前为 FakeCandidateSmokeRunner（无真实 MATLAB 环境）。"
                        + "模板已通过静态校验，但未在真实 MATLAB 上运行验证。");
    }
}
