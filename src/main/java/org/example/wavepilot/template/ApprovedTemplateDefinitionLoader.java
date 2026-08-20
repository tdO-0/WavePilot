package org.example.wavepilot.template;

import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionRegistry;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Restores declarative definitions of published templates at startup: scans
 * approved/<templateId>/<version>/experiment-definition.yaml and registers every valid
 * definition (ACTIVE versions registered last so they win the experimentTypeId slot).
 * This makes the declarative chain survive restarts without any Java map change.
 */
@Component
public class ApprovedTemplateDefinitionLoader {

    private final FileSystemTemplateRepository fileRepository;
    private final ExperimentDefinitionRegistry definitionRegistry;
    private final ExperimentDefinitionParser parser;
    private final ExperimentDefinitionValidator validator;

    public ApprovedTemplateDefinitionLoader(FileSystemTemplateRepository fileRepository,
                                            ExperimentDefinitionRegistry definitionRegistry,
                                            ExperimentDefinitionParser parser,
                                            ExperimentDefinitionValidator validator) {
        this.fileRepository = fileRepository;
        this.definitionRegistry = definitionRegistry;
        this.parser = parser;
        this.validator = validator;
    }

    @jakarta.annotation.PostConstruct
    public void loadPublishedDefinitions() {
        Path approved = fileRepository.approvedDir();
        if (!Files.isDirectory(approved)) return;
        try (Stream<Path> templateDirs = Files.list(approved)) {
            List<Path> versionDirs = templateDirs
                    .filter(Files::isDirectory)
                    .flatMap(dir -> {
                        try {
                            return Files.list(dir);
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    })
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString).reversed())
                    .toList();
            for (Path versionDir : versionDirs) {
                Path definitionFile = versionDir.resolve("experiment-definition.yaml");
                if (!Files.isRegularFile(definitionFile)) continue;
                try {
                    String yaml = Files.readString(definitionFile, StandardCharsets.UTF_8);
                    ExperimentDefinition definition = parser.parse(yaml);
                    if (validator.validate(definition).isEmpty()) {
                        definitionRegistry.register(definition);
                    }
                } catch (RuntimeException | IOException ignored) {
                    // A corrupt approved definition must never break startup; it simply
                    // stays unregistered and can be re-published as a new version.
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan approved template definitions", e);
        }
    }
}
