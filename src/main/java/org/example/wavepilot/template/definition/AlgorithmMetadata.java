package org.example.wavepilot.template.definition;

/**
 * Algorithm identity and validation boundary. {@code algorithmValidated=true} is only
 * accepted when {@code validationReference} documents an independent human/scientific
 * validation; otherwise it must stay false.
 */
public record AlgorithmMetadata(
        String name,
        String version,
        String classification,
        boolean algorithmValidated,
        String validationReference) {
}
