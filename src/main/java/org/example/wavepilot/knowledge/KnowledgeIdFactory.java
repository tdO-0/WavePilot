package org.example.wavepilot.knowledge;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class KnowledgeIdFactory {

    public String documentId(DocumentType documentType, ExperimentType experimentType,
                             String title, String source, String version) {
        String identity = documentType + "|" + experimentType + "|" + normalized(title)
                + "|" + normalized(source) + "|" + normalized(version);
        return "KB-DOC-" + sha256(identity).substring(0, 16).toUpperCase();
    }

    public String chunkId(String documentId, int chunkIndex) {
        if (chunkIndex < 0) throw new IllegalArgumentException("chunkIndex must be >= 0");
        return documentId + "-CH-" + String.format(java.util.Locale.ROOT, "%04d", chunkIndex);
    }

    private String normalized(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
