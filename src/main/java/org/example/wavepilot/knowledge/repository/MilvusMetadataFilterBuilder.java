package org.example.wavepilot.knowledge.repository;

import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MilvusMetadataFilterBuilder {

    public String build(DocumentType documentType, ExperimentType experimentType) {
        List<String> conditions = new ArrayList<>();
        if (documentType != null) conditions.add("documentType == \"" + documentType.name() + "\"");
        if (experimentType != null) conditions.add("experimentType == \"" + experimentType.name() + "\"");
        return String.join(" && ", conditions);
    }
}
