package org.example.wavepilot.knowledge.retrieval;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Offline fallback: exact query-term coverage is used only to reorder the already fused list. */
@Component
public class DeterministicDocumentReranker implements DocumentReranker {
    private static final Pattern SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}\\p{IsHan}]+");

    @Override public String name() { return "deterministic"; }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        Set<String> queryTerms = terms(query);
        return candidates.stream()
                .sorted(Comparator.<RetrievalCandidate>comparingInt(candidate ->
                                overlap(queryTerms, candidate.evidence().title() + " "
                                        + candidate.evidence().section() + " "
                                        + candidate.evidence().content()))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(RetrievalCandidate::rawScore).reversed())
                        .thenComparing(candidate -> candidate.evidence().chunkId()))
                .toList();
    }

    private int overlap(Set<String> queryTerms, String text) {
        Set<String> evidence = terms(text);
        return (int) queryTerms.stream().filter(evidence::contains).count();
    }

    private Set<String> terms(String value) {
        if (value == null) return Set.of();
        Set<String> terms = new HashSet<>(Arrays.asList(SPLIT.split(value.toLowerCase(Locale.ROOT))));
        terms.removeIf(String::isBlank);
        return terms;
    }
}
