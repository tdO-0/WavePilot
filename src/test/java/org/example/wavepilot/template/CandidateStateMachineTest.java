package org.example.wavepilot.template;

import org.example.wavepilot.template.candidate.CandidateStateMachine;
import org.example.wavepilot.template.candidate.TemplateCandidateStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CandidateStateMachineTest {

    private final CandidateStateMachine machine = new CandidateStateMachine();

    @Test
    void theMainLifecycleIsLegal() {
        machine.transition(TemplateCandidateStatus.DRAFT, TemplateCandidateStatus.GENERATED);
        machine.transition(TemplateCandidateStatus.GENERATED, TemplateCandidateStatus.VALIDATING);
        machine.transition(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.SMOKE_PENDING);
        machine.transition(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.SMOKE_PASSED);
        machine.transition(TemplateCandidateStatus.SMOKE_PASSED, TemplateCandidateStatus.REVIEW_REQUIRED);
        machine.transition(TemplateCandidateStatus.REVIEW_REQUIRED, TemplateCandidateStatus.APPROVED);
        machine.transition(TemplateCandidateStatus.APPROVED, TemplateCandidateStatus.ACTIVE);
    }

    @Test
    void withoutRealMatlabTheCandidateCanStillReachReviewRequired() {
        machine.transition(TemplateCandidateStatus.GENERATED, TemplateCandidateStatus.VALIDATING);
        machine.transition(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.SMOKE_PENDING);
        // No MATLAB: the candidate must not fake SMOKE_PASSED; it goes straight to review.
        machine.transition(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.REVIEW_REQUIRED);
    }

    @Test
    void aPassedSmokeCanGoStraightToApproval() {
        machine.transition(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.SMOKE_PASSED);
        machine.transition(TemplateCandidateStatus.SMOKE_PASSED, TemplateCandidateStatus.APPROVED);
        machine.transition(TemplateCandidateStatus.APPROVED, TemplateCandidateStatus.ACTIVE);
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThrows(IllegalStateException.class,
                () -> machine.transition(TemplateCandidateStatus.DRAFT, TemplateCandidateStatus.ACTIVE));
        assertThrows(IllegalStateException.class,
                () -> machine.transition(TemplateCandidateStatus.GENERATED, TemplateCandidateStatus.APPROVED));
        assertThrows(IllegalStateException.class,
                () -> machine.transition(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.ACTIVE));
        assertThrows(IllegalStateException.class,
                () -> machine.transition(TemplateCandidateStatus.REJECTED, TemplateCandidateStatus.APPROVED));
        // An agent can never self-approve: REVIEW_REQUIRED -> APPROVED requires the user action,
        // but the state machine itself never grants it from GENERATED or SMOKE_PENDING directly.
        assertThrows(IllegalStateException.class,
                () -> machine.transition(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.APPROVED));
    }

    @Test
    void validationFailuresCanBeRetriedAndRejected() {
        machine.transition(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.VALIDATION_FAILED);
        machine.transition(TemplateCandidateStatus.VALIDATION_FAILED, TemplateCandidateStatus.VALIDATING);
        machine.transition(TemplateCandidateStatus.VALIDATION_FAILED, TemplateCandidateStatus.REJECTED);
    }
}
