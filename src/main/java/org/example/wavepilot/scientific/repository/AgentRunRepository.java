package org.example.wavepilot.scientific.repository;

import org.example.wavepilot.scientific.model.AgentRun;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    AgentRun save(AgentRun run);
    Optional<AgentRun> findById(String runId);
    List<AgentRun> findAll();
}
