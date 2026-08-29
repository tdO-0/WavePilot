package org.example.wavepilot.scientific.ledger;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class ExperimentSpecFingerprint {
    private final ObjectMapper canonical;

    public ExperimentSpecFingerprint(ObjectMapper objectMapper) {
        canonical = objectMapper.copy().findAndRegisterModules()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String sha256(ExperimentSpec spec) {
        try {
            byte[] json = canonical.writeValueAsBytes(spec);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint ExperimentSpec", e);
        }
    }
}
