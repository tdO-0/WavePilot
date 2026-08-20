package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RealPolarAlgorithmManifestTest {

    @Test
    void manifestMatchesCodeContractAndDeclaresUnvalidatedBaseline() throws Exception {
        String path = "/matlab/templates/" + MatlabTemplateCatalog.SIMPLE_TEMPLATE
                + "/TEMPLATE_MANIFEST.json";
        try (InputStream input = getClass().getResourceAsStream(path)) {
            JsonNode manifest = new ObjectMapper().readTree(input);
            assertEquals(MatlabTemplateCatalog.SIMPLE_TEMPLATE,
                    manifest.path("templateVersion").asText());
            assertEquals(MatlabTemplateCatalog.SIMPLE_ALGORITHM_NAME,
                    manifest.path("algorithmName").asText());
            assertEquals(MatlabTemplateCatalog.SIMPLE_ALGORITHM_VERSION,
                    manifest.path("algorithmVersion").asText());
            assertEquals("BSC_BIT_FLIP_PROBABILITY",
                    manifest.path("errorRateMeaning").asText());
            assertEquals("15N/32", manifest.path("trueKRule").asText());
            assertFalse(manifest.path("algorithmValidated").asBoolean(true));
        }
    }
}
