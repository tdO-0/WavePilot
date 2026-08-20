package org.example.wavepilot.intent;

/** The real intent of a user message, resolved semantically instead of by keyword routing. */
public enum IntentType {
    GENERAL_QA,
    QUERY_TEMPLATES,
    RUN_EXPERIMENT,
    CREATE_TEMPLATE,
    ANALYZE_RESULT,
    REPLAY_EXPERIMENT,
    RUN_EVAL,
    CANCEL_EXPERIMENT
}
