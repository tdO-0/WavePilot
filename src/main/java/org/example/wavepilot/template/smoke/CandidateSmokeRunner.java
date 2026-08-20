package org.example.wavepilot.template.smoke;

import org.example.wavepilot.template.candidate.TemplateCandidate;

/** Runs a candidate template in an isolated temporary directory with a short timeout. */
public interface CandidateSmokeRunner {

    SmokeResult run(TemplateCandidate candidate);

    record SmokeResult(boolean executed, boolean passed, String report) { }
}
