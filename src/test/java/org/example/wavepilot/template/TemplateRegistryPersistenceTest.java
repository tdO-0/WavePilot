package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Published templates must survive a registry reload (restart simulation). */
class TemplateRegistryPersistenceTest {

    @TempDir Path root;

    private TemplateRootProperties props() {
        return new TemplateRootProperties(root.resolve("data/wavepilot/templates").toString());
    }

    @Test
    void publishedTemplateSurvivesARegistryReload() {
        FileSystemTemplateRepository repository =
                new FileSystemTemplateRepository(props(), new ObjectMapper().findAndRegisterModules());
        TemplateRegistry registry = new TemplateRegistry(repository);
        assertTrue(registry.list(null).isEmpty(), "fresh registry must be empty");

        registry.registerApproved(record("qpsk-awgn-ber", "1.0.0", TemplateStatus.ACTIVE));
        assertEquals(1, registry.list(null).size());

        // Simulate restart: a brand-new registry over the same repository.
        TemplateRegistry restarted =
                new TemplateRegistry(new FileSystemTemplateRepository(props(),
                        new ObjectMapper().findAndRegisterModules()));
        assertEquals(1, restarted.list(null).size());
        assertTrue(restarted.active("qpsk-awgn-ber").isPresent());
        assertEquals("1.0.0", restarted.active("qpsk-awgn-ber").get().version());
        assertFalse(restarted.active("qpsk-awgn-ber").get().algorithmValidated());
        assertFalse(restarted.active("qpsk-awgn-ber").get().operationalValidated(),
                "a generated template without smoke is not operationalValidated");
    }

    @Test
    void rollbackSwitchesActiveVersionWithoutDeletingHistory() {
        FileSystemTemplateRepository repository =
                new FileSystemTemplateRepository(props(), new ObjectMapper().findAndRegisterModules());
        TemplateRegistry registry = new TemplateRegistry(repository);
        registry.registerApproved(record("tpl", "1.0.0", TemplateStatus.INACTIVE));
        registry.registerApproved(record("tpl", "2.0.0", TemplateStatus.ACTIVE));
        assertEquals("2.0.0", registry.active("tpl").orElseThrow().version());

        registry.rollback("tpl", "1.0.0");
        assertEquals("1.0.0", registry.active("tpl").orElseThrow().version());
        assertEquals(2, registry.versions("tpl").size(), "rollback must never delete history");
        assertTrue(registry.version("tpl", "2.0.0").isPresent());
    }

    @Test
    void deactivateAndArchiveAreExplicit() {
        FileSystemTemplateRepository repository =
                new FileSystemTemplateRepository(props(), new ObjectMapper().findAndRegisterModules());
        TemplateRegistry registry = new TemplateRegistry(repository);
        registry.registerApproved(record("tpl", "1.0.0", TemplateStatus.ACTIVE));
        registry.deactivate("tpl");
        assertTrue(registry.active("tpl").isEmpty());
        registry.registerApproved(record("tpl", "1.0.0", TemplateStatus.INACTIVE));
        registry.archive("tpl", "1.0.0");
        assertTrue(registry.version("tpl", "1.0.0").map(r -> r.status() == TemplateStatus.ARCHIVED).orElse(false));
    }

    private TemplateRecord record(String templateId, String version, TemplateStatus status) {
        return new TemplateRecord(templateId, "demo-ber-awgn", "演示模板", version, "run_experiment",
                "demo", TemplateSource.AGENT_GENERATED, status, "SIMULATION_BASELINE",
                false, false, Instant.now(), Instant.now(), "def-hash", "tpl-hash", version,
                List.of("ebNoStart"), List.of("ACCURACY_CSV", "RUN_LOG"));
    }
}
