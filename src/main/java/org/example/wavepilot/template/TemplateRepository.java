package org.example.wavepilot.template;

import java.util.List;

/** Persistence boundary of formal template metadata and files. */
public interface TemplateRepository {

    List<TemplateRecord> loadAll();

    void save(TemplateRecord record);
}
