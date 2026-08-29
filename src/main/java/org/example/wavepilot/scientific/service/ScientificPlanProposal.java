package org.example.wavepilot.scientific.service;

import java.util.List;

/** Model-facing DTO: only registered capability names are accepted. */
public record ScientificPlanProposal(List<String> capabilities) {
    public ScientificPlanProposal {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
