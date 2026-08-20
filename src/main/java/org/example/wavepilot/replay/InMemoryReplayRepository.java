package org.example.wavepilot.replay;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryReplayRepository implements ReplayRepository {

    private final ConcurrentMap<String, ReplayRecord> replays = new ConcurrentHashMap<>();

    @Override
    public ReplayRecord save(ReplayRecord record) {
        replays.put(record.getReplayId(), record);
        return record;
    }

    @Override
    public Optional<ReplayRecord> findById(String replayId) {
        return Optional.ofNullable(replays.get(replayId));
    }

    @Override
    public List<ReplayRecord> findAll() {
        return replays.values().stream()
                .sorted(Comparator.comparing(ReplayRecord::getCreatedAt).reversed())
                .toList();
    }
}
