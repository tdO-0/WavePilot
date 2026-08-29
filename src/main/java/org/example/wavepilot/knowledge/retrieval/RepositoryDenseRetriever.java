package org.example.wavepilot.knowledge.retrieval;

import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.repository.WavePilotKnowledgeRepository;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RepositoryDenseRetriever implements DenseRetriever {
    private final WavePilotKnowledgeRepository repository;

    public RepositoryDenseRetriever(WavePilotKnowledgeRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<RetrievalCandidate> search(KnowledgeSearchRequest request, int candidateK) {
        KnowledgeSearchRequest expanded = new KnowledgeSearchRequest(request.query(), candidateK,
                request.documentType(), request.experimentType());
        return repository.search(expanded).stream()
                .map(result -> new RetrievalCandidate(
                        result.withScoreAndMethod(result.similarityScore(), "DENSE"),
                        result.similarityScore()))
                .toList();
    }
}
