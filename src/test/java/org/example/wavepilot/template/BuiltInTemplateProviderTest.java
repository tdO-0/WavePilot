package org.example.wavepilot.template;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltInTemplateProviderTest {

    private final BuiltInTemplateProvider provider = new BuiltInTemplateProvider();

    @Test
    void builtInTemplatesAreActiveButNeverAlgorithmValidated() {
        List<TemplateRecord> records = provider.builtInRecords();
        assertEquals(2, records.size());
        for (TemplateRecord record : records) {
            assertEquals(TemplateSource.BUILT_IN, record.source());
            assertEquals(TemplateStatus.ACTIVE, record.status());
            assertTrue(record.operationalValidated(), "built-ins ran the real MATLAB smoke");
            assertFalse(record.algorithmValidated(), "built-ins are never algorithm-validated");
        }
        assertTrue(records.stream().anyMatch(record ->
                record.templateId().equals("polar-k-identification-simple-v1")));
        assertTrue(records.stream().anyMatch(record ->
                record.templateId().equals("polar-k-integration-fixture-v1")));
    }

    @Test
    void builtInRecordsNeverContainAbsolutePaths() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(provider.builtInRecords());
        assertTrue(!json.contains("C:\\") && !json.contains("D:\\"),
                "built-in records must not expose absolute paths");
    }
}
