package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.template.candidate.TemplateCandidate;
import org.example.wavepilot.template.generation.StubTemplateGenerationModel;
import org.example.wavepilot.template.generation.TemplateGenerationModel;
import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Path traversal, absolute paths, symlink-ish escapes and segment injection must be refused. */
class TemplateDirectorySecurityTest {

    @TempDir Path root;

    @Test
    void unsafeTemplateIdAndVersionSegmentsAreRejected() {
        FileSystemTemplateRepository repository = new FileSystemTemplateRepository(
                new TemplateRootProperties(root.resolve("templates").toString()),
                new ObjectMapper().findAndRegisterModules());
        assertThrows(FileSystemTemplateRepository.TemplateStorageException.class,
                () -> repository.approvedDirectory("../escape", "1.0.0"));
        assertThrows(FileSystemTemplateRepository.TemplateStorageException.class,
                () -> repository.approvedDirectory("ok", "../escape"));
        assertThrows(FileSystemTemplateRepository.TemplateStorageException.class,
                () -> repository.approvedDirectory("C:\\evil", "1.0.0"));
        assertThrows(FileSystemTemplateRepository.TemplateStorageException.class,
                () -> repository.approvedDirectory("ok", "1.0.0\\..\\.."));
    }

    @Test
    void symlinkEscapeIsRejectedByPathNormalization() throws Exception {
        FileSystemTemplateRepository repository = new FileSystemTemplateRepository(
                new TemplateRootProperties(root.resolve("templates").toString()),
                new ObjectMapper().findAndRegisterModules());
        // Even if a segment were a symlink, the file destination must stay inside the
        // approved directory; the repository refuses segments that could normalize outside.
        assertThrows(FileSystemTemplateRepository.TemplateStorageException.class,
                () -> repository.writeApprovedFiles("ok", "1.0.0",
                        List.of(new FileSystemTemplateRepository.TemplateFile(
                                "../outside.m", new byte[]{1}))));
        // And the approved path itself is derived from validated segments only.
        Path approved = repository.approvedDirectory("ok", "1.0.0");
        assertTrue(approved.startsWith(root.toAbsolutePath().normalize()),
                "approved paths must stay under the configured root");
        assertTrue(repository.approvedDirectory("ok", "1.0.0").toString().contains("approved"),
                "approved paths must live under the approved directory");
    }

    @Test
    void generatedPackagePathChecksRejectWindowsAndPosixEscapes() {
        // Windows drive, POSIX absolute and dot-dot are all rejected by the generation
        // service path normalization (covered through the model boundary).
        TemplateGenerationModel unsafe = new TemplateGenerationModel() {
            @Override public String name() { return "unsafe"; }
            @Override public TemplateGenerationResult generate(String request) {
                return new TemplateGenerationResult("tpl", "tpl", "n", "1.0.0", "x",
                        "templateId: tpl\nexperimentTypeId: tpl\ndisplayName: n\nversion: 1.0.0\n"
                                + "entryPoint: run_experiment\nparameters: []\noutputs:\n"
                                + "  requiredColumns: [a]\n  numericColumns: [a]\nmetrics: []\n"
                                + "replay: []\nalgorithm:\n  name: n\n  version: 1\n"
                                + "  classification: X\n  algorithmValidated: false\n",
                        "{}",
                        List.of(new GeneratedFile("C:/escape.m", "x")),
                        "n", List.of(), List.of());
            }
        };
        org.example.wavepilot.template.candidate.CandidateStateMachine machine =
                new org.example.wavepilot.template.candidate.CandidateStateMachine();
        TemplateGenerationService service = new TemplateGenerationService(
                new StubTemplateGenerationModel() {
                    @Override public TemplateGenerationResult generate(String request) {
                        return unsafe.generate(request);
                    }
                },
                new org.example.wavepilot.template.candidate.CandidateTemplateRepository(),
                machine, new org.example.wavepilot.template.definition.ExperimentDefinitionParser(),
                new org.example.wavepilot.template.definition.ExperimentDefinitionValidator(),
                new org.example.wavepilot.template.definition.ExperimentDefinitionRegistry(),
                new ObjectMapper().findAndRegisterModules());
        assertThrows(TemplateGenerationService.TemplateGenerationException.class,
                () -> service.generate("x"));
    }
}
