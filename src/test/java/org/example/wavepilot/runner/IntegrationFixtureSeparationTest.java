package org.example.wavepilot.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegrationFixtureSeparationTest {

    @Test
    void fixtureAndSimpleAlgorithmHaveDifferentWhitelistedIdentities() throws Exception {
        var real = MatlabTemplateCatalog.require(MatlabTemplateCatalog.SIMPLE_TEMPLATE);
        var fixture = MatlabTemplateCatalog.require(MatlabTemplateCatalog.INTEGRATION_FIXTURE);
        assertNotEquals(real.version(), fixture.version());
        assertNotEquals(real.resourceRoot(), fixture.resourceRoot());

        try (InputStream input = getClass().getResourceAsStream(
                fixture.resourceRoot() + "/TEMPLATE_MANIFEST.json")) {
            JsonNode manifest = new ObjectMapper().readTree(input);
            assertEquals("INTEGRATION_FIXTURE", manifest.path("classification").asText());
            assertFalse(manifest.path("algorithmValidated").asBoolean(true));
        }
        try (InputStream input = getClass().getResourceAsStream(
                fixture.resourceRoot() + "/run_experiment.m")) {
            String source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("channelEnergy"));
            assertFalse(source.contains("polar_generator_matrix"));
        }
    }
}
