package org.example.wavepilot.template;

import org.example.wavepilot.intent.ExperimentIntent;
import org.example.wavepilot.template.definition.ExperimentDefinition;
import org.example.wavepilot.template.definition.TemplateCapabilities;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic template resolution: match a resolved {@link ExperimentIntent} against the
 * ACTIVE templates' capability metadata (family/modulation/coding/channel/tags/aliases),
 * with a fallback to id/type/display-name aliasing for legacy templates. Returns MATCHED,
 * NO_MATCH or AMBIGUOUS; a MATCHED result always names the template and the score, and
 * AMBIGUOUS lists the alternatives for the user to choose — the Agent never picks one
 * arbitrarily.
 */
@Component
public class TemplateResolver {

    public Resolution resolve(ExperimentIntent intent, List<ExperimentDefinition> activeDefinitions) {
        if (activeDefinitions == null || activeDefinitions.isEmpty()) {
            return new Resolution(Verdict.NO_MATCH, null, 0, List.of(), List.of());
        }
        List<Scored> scored = new ArrayList<>();
        for (ExperimentDefinition definition : activeDefinitions) {
            int score = score(intent, definition);
            if (score > 0) {
                scored.add(new Scored(definition.templateId(), score, reasons(intent, definition)));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        if (scored.isEmpty()) {
            return new Resolution(Verdict.NO_MATCH, null, 0, List.of(), List.of());
        }
        Scored best = scored.get(0);
        if (scored.size() > 1 && best.score() == scored.get(1).score()) {
            return new Resolution(Verdict.AMBIGUOUS, null, best.score(), List.of(),
                    scored.stream().map(Scored::templateId).toList());
        }
        return new Resolution(Verdict.MATCHED, best.templateId(), best.score(),
                best.reasons(), List.of());
    }

    private int score(ExperimentIntent intent, ExperimentDefinition definition) {
        TemplateCapabilities caps = definition.capabilities();
        if (caps == null) {
            // Legacy template without capabilities: alias matching only.
            return legacyAliasScore(intent, definition);
        }
        // Hard exclusions: an explicitly stated modulation/coding/channel that the template
        // does not provide disqualifies it (OFDM must never match a QPSK template).
        if (definitelyDiffers(intent.modulation(), caps.modulation())
                || definitelyDiffers(intent.coding(), caps.coding())
                || definitelyDiffers(intent.channel(), caps.channel())) {
            return 0;
        }
        int score = 0;
        // experimentFamily is broad ("MODULATION" covers QPSK/OFDM alike) — auxiliary only.
        score += equal(intent.experimentFamily(), caps.experimentFamily(), 1);
        score += equal(intent.modulation(), caps.modulation(), 4);
        score += equal(intent.coding(), caps.coding(), 4);
        score += equal(intent.channel(), caps.channel(), 4);
        if (intent.objective() != null && caps.objective() != null
                && containsIgnoreCase(caps.objective(), intent.objective())) {
            score += 2;
        }
        for (String tag : caps.tags()) {
            if (intent.semanticTags().stream().anyMatch(t -> containsIgnoreCase(tag, t))) {
                score += 2;
            }
        }
        return score;
    }

    /** Both sides state a concrete value and they are not equivalent. */
    private boolean definitelyDiffers(String intentValue, String capabilityValue) {
        if (intentValue == null || capabilityValue == null) return false;
        if (containsIgnoreCase(intentValue, capabilityValue)
                || containsIgnoreCase(capabilityValue, intentValue)) {
            return false;
        }
        return true;
    }

    private int legacyAliasScore(ExperimentIntent intent, ExperimentDefinition definition) {
        String haystack = (definition.templateId() + " " + definition.experimentTypeId() + " "
                + definition.displayName()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (intent.modulation() != null && haystack.contains(intent.modulation().toLowerCase(Locale.ROOT))) {
            score += 3;
        }
        if (intent.coding() != null && haystack.contains(intent.coding().toLowerCase(Locale.ROOT))) {
            score += 3;
        }
        if (intent.channel() != null && haystack.contains(intent.channel().toLowerCase(Locale.ROOT))) {
            score += 3;
        }
        for (String tag : intent.semanticTags()) {
            if (tag.length() >= 3 && haystack.contains(tag.toLowerCase(Locale.ROOT))) {
                score += 1;
            }
        }
        return score;
    }

    private List<String> reasons(ExperimentIntent intent, ExperimentDefinition definition) {
        List<String> reasons = new ArrayList<>();
        TemplateCapabilities caps = definition.capabilities();
        if (caps != null) {
            if (equal(intent.modulation(), caps.modulation(), 1) > 0) reasons.add("modulation=" + caps.modulation());
            if (equal(intent.coding(), caps.coding(), 1) > 0) reasons.add("coding=" + caps.coding());
            if (equal(intent.channel(), caps.channel(), 1) > 0) reasons.add("channel=" + caps.channel());
        }
        if (reasons.isEmpty()) reasons.add("id/type alias match");
        return reasons;
    }

    private int equal(String a, String b, int weight) {
        if (a == null || b == null) return 0;
        return containsIgnoreCase(a, b) || containsIgnoreCase(b, a) ? weight : 0;
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public enum Verdict { MATCHED, NO_MATCH, AMBIGUOUS }

    public record Scored(String templateId, int score, List<String> reasons) { }

    public record Resolution(Verdict verdict, String matchedTemplateId, int score,
                             List<String> reasons, List<String> alternativeTemplates) { }
}
