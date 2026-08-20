package org.example.wavepilot.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class ReplayFingerprintService {

    private final ObjectMapper canonicalMapper;

    public ReplayFingerprintService(ObjectMapper objectMapper) {
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String fingerprint(ReplayFingerprintInput input) {
        if (input == null || input.experimentSpec() == null) {
            throw new IllegalArgumentException("Replay fingerprint input and ExperimentSpec are required");
        }
        try {
            byte[] canonicalJson = canonicalMapper.writeValueAsBytes(input);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Replay input cannot be normalized", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** Canonical, field-ordered JSON used for deterministic comparisons and manifests. */
    public String canonicalJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Value cannot be normalized to canonical JSON", e);
        }
    }
}
