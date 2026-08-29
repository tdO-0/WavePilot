package org.example.wavepilot.scientific.model;

/** The only plan capabilities. No capability accepts source code or shell commands. */
public enum ScientificCapability {
    RETRIEVE_EVIDENCE,
    EXECUTE_VALIDATED_EXPERIMENT,
    VERIFY_GROUNDED_RESULT,
    ANALYZE_RESULT,
    REPLAN_EXPERIMENT,
    /** Legacy name accepted only for old checkpoints. */
    REPLAN_BOUNDED_PARAMETERS
}
