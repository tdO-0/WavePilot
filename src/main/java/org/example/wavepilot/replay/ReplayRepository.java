package org.example.wavepilot.replay;

import java.util.List;
import java.util.Optional;

public interface ReplayRepository {
    ReplayRecord save(ReplayRecord record);
    Optional<ReplayRecord> findById(String replayId);
    List<ReplayRecord> findAll();
}
