package org.example.wavepilot.template.smoke;

import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.CandidateTemplateRepository;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Runs the smoke step for a candidate. Without a real MATLAB environment the fake runner
 * reports executed=false; the candidate then moves to REVIEW_REQUIRED with an explicit
 * "MATLAB Smoke 未执行" report and can never claim SMOKE_PASSED.
 */
@Service
public class CandidateSmokeService {

    private final CandidateTemplateRepository candidateRepository;
    private final CandidateStateMachine stateMachine;
    private final CandidateSmokeRunner smokeRunner;

    public CandidateSmokeService(CandidateTemplateRepository candidateRepository,
                                 CandidateStateMachine stateMachine,
                                 CandidateSmokeRunner smokeRunner) {
        this.candidateRepository = candidateRepository;
        this.stateMachine = stateMachine;
        this.smokeRunner = smokeRunner;
    }

    public TemplateCandidate smoke(String candidateId) {
        TemplateCandidate candidate = candidateRepository.findById(candidateId)
                .orElseThrow(() -> new NoSuchElementException("Candidate not found: " + candidateId));
        if (candidate.status() != TemplateCandidateStatus.SMOKE_PENDING
                && candidate.status() != TemplateCandidateStatus.SMOKE_FAILED
                && candidate.status() != TemplateCandidateStatus.REVIEW_REQUIRED) {
            throw new IllegalStateException("Candidate " + candidateId
                    + " is not in a smoke-eligible state: " + candidate.status());
        }
        CandidateSmokeRunner.SmokeResult result = smokeRunner.run(candidate);
        TemplateCandidate updated = candidate.withSmoke(result.report(), result.executed());
        if (result.executed() && result.passed()) {
            stateMachine.transition(candidate.status(), TemplateCandidateStatus.SMOKE_PASSED);
            updated = updated.withStatus(TemplateCandidateStatus.SMOKE_PASSED);
        } else if (result.executed()) {
            stateMachine.transition(candidate.status(), TemplateCandidateStatus.SMOKE_FAILED);
            updated = updated.withStatus(TemplateCandidateStatus.SMOKE_FAILED)
                    .withFailure("MATLAB Smoke 失败");
        } else {
            // No real MATLAB: do not fake SMOKE_PASSED, go straight to human review.
            stateMachine.transition(candidate.status(), TemplateCandidateStatus.REVIEW_REQUIRED);
            updated = updated.withStatus(TemplateCandidateStatus.REVIEW_REQUIRED);
        }
        candidateRepository.save(updated);
        return updated;
    }
}
