package org.example.wavepilot.template;

import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.ExperimentDefinitionParser;
import org.example.wavepilot.template.definition.ExperimentDefinitionValidator;
import org.example.wavepilot.template.definition.ParameterDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperimentDefinitionParseTest {

    private final ExperimentDefinitionParser parser = new ExperimentDefinitionParser();
    private final ExperimentDefinitionValidator validator = new ExperimentDefinitionValidator();

    @Test
    void parsesTheDemoDefinitionIntoTypedRecords() {
        ExperimentDefinition definition = parser.parse(DeclarativeTestSupport.DEMO_DEFINITION_YAML);
        assertEquals("demo-ber-awgn", definition.templateId());
        assertEquals("demo-ber-awgn", definition.experimentTypeId());
        assertEquals("run_experiment", definition.entryPoint());
        assertEquals(6, definition.parameters().size());
        assertEquals(List.of("ebNo", "berSim", "berTheory"), definition.outputs().requiredColumns());
        assertEquals(2, definition.metrics().size());
        assertEquals(2, definition.replay().size());
        assertFalse(definition.algorithm().algorithmValidated());
        assertFalse(definition.customExtensionRequired());
        ParameterDefinition sweep = definition.parameters().stream()
                .filter(ParameterDefinition::sweep).findFirst().orElseThrow();
        assertEquals(1.0, sweep.step());
        assertTrue(validator.validate(definition).isEmpty());
    }

    @Test
    void unknownFieldsAreRejectedInsteadOfSilentlyIgnored() {
        String yaml = DeclarativeTestSupport.DEMO_DEFINITION_YAML
                .replace("algorithmValidated: false", "algorithmValidated: false\ntypoField: oops");
        assertThrows(ExperimentDefinitionParser.DefinitionParseException.class,
                () -> parser.parse(yaml));
    }

    @Test
    void algorithmValidatedTrueRequiresAnIndependentValidationReference() {
        String yaml = DeclarativeTestSupport.DEMO_DEFINITION_YAML
                .replace("algorithmValidated: false", "algorithmValidated: true");
        assertThrows(ExperimentDefinitionParser.DefinitionParseException.class, () -> parser.parse(yaml));
    }

    @Test
    void schemaValidationCatchesBrokenContracts() {
        String broken = DeclarativeTestSupport.DEMO_DEFINITION_YAML
                .replace("numericColumns: [ebNo, berSim, berTheory]",
                        "numericColumns: [notAColumn]");
        ExperimentDefinition definition = parser.parse(broken);
        List<String> errors = validator.validate(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("numericColumns")),
                "numeric column must be a required column: " + errors);
    }

    @Test
    void replayColumnMustExistInTheCsvContract() {
        String broken = DeclarativeTestSupport.DEMO_DEFINITION_YAML
                .replace("comparisonColumn: berTheory", "comparisonColumn: missingColumn");
        ExperimentDefinition definition = parser.parse(broken);
        List<String> errors = validator.validate(definition);
        assertTrue(errors.stream().anyMatch(error -> error.contains("Replay comparisonColumn")),
                "replay column must be declared: " + errors);
    }
}
