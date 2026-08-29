package org.example.wavepilot.scientific.service;

import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ScientificCapability;

import java.util.Set;

public interface ScientificPlanModel {
    ScientificPlanProposal propose(AgentRun run, int iteration,
                                   Set<ScientificCapability> registeredCapabilities);
}
