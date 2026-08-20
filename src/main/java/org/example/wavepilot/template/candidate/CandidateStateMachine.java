package org.example.wavepilot.template.candidate;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit candidate state machine; illegal transitions are rejected. */
@Component
public class CandidateStateMachine {

    private final Map<TemplateCandidateStatus, Set<TemplateCandidateStatus>> transitions =
            new EnumMap<>(TemplateCandidateStatus.class);

    public CandidateStateMachine() {
        transitions.put(TemplateCandidateStatus.DRAFT,
                EnumSet.of(TemplateCandidateStatus.GENERATED, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.GENERATED,
                EnumSet.of(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.VALIDATING,
                EnumSet.of(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.VALIDATION_FAILED,
                        TemplateCandidateStatus.REQUIRES_CUSTOM_EXTENSION, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.VALIDATION_FAILED,
                EnumSet.of(TemplateCandidateStatus.VALIDATING, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.SMOKE_PENDING,
                EnumSet.of(TemplateCandidateStatus.SMOKE_PASSED, TemplateCandidateStatus.SMOKE_FAILED,
                        TemplateCandidateStatus.REVIEW_REQUIRED, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.SMOKE_PASSED,
                EnumSet.of(TemplateCandidateStatus.REVIEW_REQUIRED, TemplateCandidateStatus.APPROVED,
                        TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.SMOKE_FAILED,
                EnumSet.of(TemplateCandidateStatus.SMOKE_PENDING, TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.REVIEW_REQUIRED,
                EnumSet.of(TemplateCandidateStatus.APPROVED, TemplateCandidateStatus.REJECTED,
                        TemplateCandidateStatus.SMOKE_PENDING));
        transitions.put(TemplateCandidateStatus.APPROVED,
                EnumSet.of(TemplateCandidateStatus.ACTIVE, TemplateCandidateStatus.ROLLED_BACK,
                        TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.ACTIVE,
                EnumSet.of(TemplateCandidateStatus.ROLLED_BACK, TemplateCandidateStatus.ARCHIVED,
                        TemplateCandidateStatus.REJECTED));
        transitions.put(TemplateCandidateStatus.REJECTED, EnumSet.noneOf(TemplateCandidateStatus.class));
        transitions.put(TemplateCandidateStatus.ARCHIVED, EnumSet.noneOf(TemplateCandidateStatus.class));
        transitions.put(TemplateCandidateStatus.ROLLED_BACK, EnumSet.noneOf(TemplateCandidateStatus.class));
        transitions.put(TemplateCandidateStatus.REQUIRES_CUSTOM_EXTENSION,
                EnumSet.of(TemplateCandidateStatus.REJECTED));
    }

    public void transition(TemplateCandidateStatus current, TemplateCandidateStatus next) {
        if (!transitions.getOrDefault(current, Set.of()).contains(next)) {
            throw new IllegalStateException("Illegal candidate transition: " + current + " -> " + next);
        }
    }
}
