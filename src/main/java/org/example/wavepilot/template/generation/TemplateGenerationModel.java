package org.example.wavepilot.template.generation;

import org.example.wavepilot.intent.ExperimentIntent;

import java.util.List;

/**
 * The candidate-generation model boundary. A model produces a structured candidate package
 * (definition YAML, manifest JSON and MATLAB files) but can never write to the formal
 * template directory itself; the service validates and writes everything.
 *
 * <p>Generation is schema driven: the model receives the resolved {@link ExperimentIntent}
 * and designs the template from the experiment semantics — template id, parameter schema,
 * output columns and metrics follow the task, never Java if/else.
 */
public interface TemplateGenerationModel {

    String name();

    TemplateGenerationResult generate(String request);

    /**
     * Schema-driven generation from a resolved experiment intent. Default delegates to the
     * plain-text entry so legacy anonymous test doubles keep compiling; production models
     * implement the design path.
     */
    default TemplateGenerationResult generate(ExperimentTemplateDesignRequest request) {
        return generate(request.userRequest());
    }

    record ExperimentTemplateDesignRequest(
            ExperimentIntent intent,
            String userRequest,
            List<String> requestedOutputs,
            List<String> knownParameters,
            List<String> constraints) {

        public ExperimentTemplateDesignRequest {
            requestedOutputs = requestedOutputs == null ? List.of() : List.copyOf(requestedOutputs);
            knownParameters = knownParameters == null ? List.of() : List.copyOf(knownParameters);
            constraints = constraints == null ? List.of() : List.copyOf(constraints);
        }
    }

    record TemplateGenerationResult(
            String templateId,
            String experimentTypeId,
            String displayName,
            String version,
            String description,
            String definitionYaml,
            String manifestJson,
            List<GeneratedFile> files,
            String generationNotes,
            List<String> assumptions,
            List<String> unresolvedQuestions) {

        public TemplateGenerationResult {
            files = files == null ? List.of() : List.copyOf(files);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            unresolvedQuestions = unresolvedQuestions == null ? List.of() : List.copyOf(unresolvedQuestions);
        }
    }

    record GeneratedFile(String relativePath, String content) { }
}
