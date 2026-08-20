package org.example.wavepilot.runner;

import org.example.wavepilot.experiment.model.ExperimentType;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Code-level whitelist of versioned MATLAB templates. Every template declares the
 * experiment type it serves, so validation, result contracts and reporting can dispatch on
 * the type without ever executing arbitrary scripts. Adding a new experiment type means
 * adding its fixed template here (and a matching contract validator), never dynamic loading.
 */
public final class MatlabTemplateCatalog {

    public static final String SIMPLE_TEMPLATE = "polar-k-identification-simple-v1";
    public static final String INTEGRATION_FIXTURE = "polar-k-integration-fixture-v1";
    public static final String SIMPLE_ALGORITHM_NAME = "polar-bsc-binomial-k-baseline";
    public static final String SIMPLE_ALGORITHM_VERSION = "1.0.0";
    public static final String MATLAB_ENTRYPOINT = "run_experiment('matlab-input.json', '.')";

    private static final Map<String, MatlabTemplate> TEMPLATES = Map.of(
            SIMPLE_TEMPLATE, new MatlabTemplate(
                    SIMPLE_TEMPLATE,
                    ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                    "/matlab/templates/" + SIMPLE_TEMPLATE,
                    List.of(
                            "run_experiment.m",
                            "load_and_validate_spec.m",
                            "run_parameter_sweep.m",
                            "run_single_case.m",
                            "export_results.m",
                            "plot_results.m",
                            "algorithm/polar_generator_matrix.m",
                            "algorithm/bec_reliability_order.m",
                            "algorithm/estimate_k_binomial.m",
                            "TEMPLATE_MANIFEST.json",
                            "README.md")),
            INTEGRATION_FIXTURE, new MatlabTemplate(
                    INTEGRATION_FIXTURE,
                    ExperimentType.POLAR_CODE_K_IDENTIFICATION,
                    "/matlab/templates/" + INTEGRATION_FIXTURE,
                    List.of("run_experiment.m", "TEMPLATE_MANIFEST.json", "README.md")));

    private MatlabTemplateCatalog() {
    }

    public static MatlabTemplate require(String templateName) {
        if (templateName == null || templateName.isBlank()) {
            throw new NoSuchElementException("A fixed MATLAB template name is required");
        }
        MatlabTemplate template = TEMPLATES.get(templateName);
        if (template == null) {
            throw new NoSuchElementException("Unsupported fixed MATLAB template: " + templateName);
        }
        return template;
    }

    public static MatlabTemplate requireByExperimentType(ExperimentType experimentType) {
        if (experimentType == null) {
            throw new NoSuchElementException("An experiment type is required");
        }
        return TEMPLATES.values().stream()
                .filter(template -> template.experimentType() == experimentType)
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException(
                        "No fixed MATLAB template is registered for experiment type: " + experimentType));
    }

    public static boolean hasTemplateFor(ExperimentType experimentType) {
        return TEMPLATES.values().stream().anyMatch(template -> template.experimentType() == experimentType);
    }

    public record MatlabTemplate(String version, ExperimentType experimentType,
                                 String resourceRoot, List<String> resourceFiles) {
        public MatlabTemplate {
            resourceFiles = List.copyOf(resourceFiles);
        }
    }
}
