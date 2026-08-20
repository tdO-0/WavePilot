package org.example.wavepilot.template.candidate;

/** Lifecycle states of a candidate template package. */
public enum TemplateCandidateStatus {
    DRAFT,
    GENERATED,
    VALIDATING,
    VALIDATION_FAILED,
    SMOKE_PENDING,
    SMOKE_PASSED,
    SMOKE_FAILED,
    REVIEW_REQUIRED,
    APPROVED,
    ACTIVE,
    REJECTED,
    ARCHIVED,
    ROLLED_BACK,
    REQUIRES_CUSTOM_EXTENSION
}
