package org.example.wavepilot.knowledge.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "wavepilot.knowledge.hybrid")
public class HybridRetrievalProperties {
    private int denseCandidateK = 20;
    private int sparseCandidateK = 20;
    private int resultTopK = 5;
    private int rrfK = 60;
    private String reranker = "deterministic";

    public int getDenseCandidateK() { return clamp(denseCandidateK, 1, 100); }
    public void setDenseCandidateK(int denseCandidateK) { this.denseCandidateK = denseCandidateK; }
    public int getSparseCandidateK() { return clamp(sparseCandidateK, 1, 100); }
    public void setSparseCandidateK(int sparseCandidateK) { this.sparseCandidateK = sparseCandidateK; }
    public int getResultTopK() { return clamp(resultTopK, 1, 50); }
    public void setResultTopK(int resultTopK) { this.resultTopK = resultTopK; }
    public int getRrfK() { return clamp(rrfK, 1, 1_000); }
    public void setRrfK(int rrfK) { this.rrfK = rrfK; }
    public String getReranker() { return reranker; }
    public void setReranker(String reranker) { this.reranker = reranker; }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
