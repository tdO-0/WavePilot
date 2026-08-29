package org.example.wavepilot.scientific.ledger;

import java.util.List;
import java.util.Optional;

public interface ExecutionLedgerRepository {
    ExecutionLedgerEntry save(ExecutionLedgerEntry entry);
    Optional<ExecutionLedgerEntry> findByExecutionId(String executionId);
    List<ExecutionLedgerEntry> findByRunId(String runId);
}
