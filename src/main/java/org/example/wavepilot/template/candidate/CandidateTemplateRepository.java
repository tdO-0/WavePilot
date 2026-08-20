package org.example.wavepilot.template.candidate;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class CandidateTemplateRepository {

    private final ConcurrentMap<String, TemplateCandidate> candidates = new ConcurrentHashMap<>();

    public TemplateCandidate save(TemplateCandidate candidate) {
        candidates.put(candidate.candidateId(), candidate);
        return candidate;
    }

    public Optional<TemplateCandidate> findById(String candidateId) {
        return Optional.ofNullable(candidates.get(candidateId));
    }

    public List<TemplateCandidate> findAll() {
        return candidates.values().stream()
                .sorted(java.util.Comparator.comparing(TemplateCandidate::createdAt).reversed())
                .toList();
    }
}
