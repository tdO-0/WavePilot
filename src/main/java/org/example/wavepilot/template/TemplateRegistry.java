package org.example.wavepilot.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * Unified formal-template registry: built-in templates plus approved agent-generated ones.
 * Version switching (activate/deactivate/archive/rollback) never deletes files, it only
 * changes which version is ACTIVE. Metadata is persisted through the repository and can be
 * reloaded explicitly.
 */
@Component
public class TemplateRegistry {

    private final ConcurrentMap<String, List<TemplateRecord>> byTemplateId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> activeVersion = new ConcurrentHashMap<>();
    private final TemplateRepository repository;

    public TemplateRegistry(TemplateRepository repository) {
        this.repository = repository;
        reload();
    }

    public synchronized void reload() {
        byTemplateId.clear();
        activeVersion.clear();
        List<TemplateRecord> loaded = new ArrayList<>(repository.loadAll());
        // Normalize legacy data: at most one ACTIVE version per templateId (the newest
        // one wins); any other ACTIVE records are demoted and persisted so the registry
        // file is repaired in place instead of drifting further.
        Map<String, List<TemplateRecord>> byId = new java.util.LinkedHashMap<>();
        for (TemplateRecord record : loaded) {
            byId.computeIfAbsent(record.templateId(), ignored -> new ArrayList<>()).add(record);
        }
        for (List<TemplateRecord> versions : byId.values()) {
            versions.sort(Comparator.comparing(TemplateRecord::version).reversed());
            TemplateRecord newestActive = versions.stream()
                    .filter(record -> record.status() == TemplateStatus.ACTIVE)
                    .findFirst().orElse(null);
            for (TemplateRecord record : versions) {
                if (record.status() == TemplateStatus.ACTIVE
                        && (newestActive == null || !newestActive.version().equals(record.version()))) {
                    repository.save(flip(record, TemplateStatus.INACTIVE));
                    loaded.set(loaded.indexOf(record), flip(record, TemplateStatus.INACTIVE));
                }
            }
        }
        for (TemplateRecord record : loaded) {
            byTemplateId.computeIfAbsent(record.templateId(), ignored -> new ArrayList<>()).add(record);
            if (record.status() == TemplateStatus.ACTIVE) {
                activeVersion.put(record.templateId(), record.version());
            }
        }
        byTemplateId.values().forEach(versions ->
                versions.sort(Comparator.comparing(TemplateRecord::version).reversed()));
    }

    public synchronized void registerApproved(TemplateRecord record) {
        if (record.status() == TemplateStatus.ACTIVE) {
            // Publishing a new ACTIVE version demotes the previous active version, exactly
            // like a rollback flips statuses; only one version may ever be ACTIVE.
            Optional<TemplateRecord> oldActive = active(record.templateId());
            if (oldActive.isPresent() && !oldActive.get().version().equals(record.version())) {
                repository.save(flip(oldActive.get(), TemplateStatus.INACTIVE));
            }
            activeVersion.put(record.templateId(), record.version());
        }
        byTemplateId.computeIfAbsent(record.templateId(), ignored -> new ArrayList<>()).add(record);
        repository.save(record);
    }

    public synchronized void setActiveVersion(String templateId, String version) {
        Optional<TemplateRecord> target = version(templateId, version);
        if (target.isEmpty()) {
            throw new NoSuchElementException("No version " + version + " of template " + templateId);
        }
        TemplateRecord oldActive = active(templateId).orElse(null);
        if (oldActive != null && !oldActive.version().equals(version)) {
            repository.save(flip(oldActive, TemplateStatus.INACTIVE));
        }
        repository.save(flip(target.get(), TemplateStatus.ACTIVE));
        activeVersion.put(templateId, version);
        reload();
    }

    public synchronized void deactivate(String templateId) {
        Optional<TemplateRecord> current = active(templateId);
        if (current.isEmpty()) {
            throw new NoSuchElementException("Template has no active version: " + templateId);
        }
        repository.save(flip(current.get(), TemplateStatus.INACTIVE));
        activeVersion.remove(templateId);
        reload();
    }

    public synchronized void archive(String templateId, String version) {
        Optional<TemplateRecord> target = version(templateId, version);
        if (target.isEmpty()) {
            throw new NoSuchElementException("No version " + version + " of template " + templateId);
        }
        if (target.get().status() == TemplateStatus.ACTIVE) {
            activeVersion.remove(templateId);
        }
        repository.save(flip(target.get(), TemplateStatus.ARCHIVED));
        reload();
    }

    public synchronized void rollback(String templateId, String version) {
        // Rollback never deletes files: it only switches the active version.
        setActiveVersion(templateId, version);
    }

    /**
     * Template summary list: one row per templateId. The row is the ACTIVE version when one
     * exists, otherwise the newest version. Full per-version history stays available
     * through {@link #versions(String)} so the list never looks like a version picker.
     */
    public List<TemplateRecord> list(Predicate<TemplateRecord> filter) {
        java.util.Map<String, TemplateRecord> byId = new java.util.LinkedHashMap<>();
        for (List<TemplateRecord> versions : byTemplateId.values()) {
            for (TemplateRecord record : versions) {
                if (filter != null && !filter.test(record)) continue;
                TemplateRecord current = byId.get(record.templateId());
                if (current == null || prefer(record, current)) {
                    byId.put(record.templateId(), record);
                }
            }
        }
        return byId.values().stream()
                .sorted(Comparator.comparing(TemplateRecord::templateId))
                .toList();
    }

    private boolean prefer(TemplateRecord candidate, TemplateRecord current) {
        if (candidate.status() == TemplateStatus.ACTIVE && current.status() != TemplateStatus.ACTIVE) {
            return true;
        }
        if (current.status() == TemplateStatus.ACTIVE) {
            return false;
        }
        return candidate.version().compareTo(current.version()) > 0;
    }

    public List<TemplateRecord> versions(String templateId) {
        List<TemplateRecord> versions = byTemplateId.get(templateId);
        return versions == null ? List.of() : List.copyOf(versions);
    }

    public Optional<TemplateRecord> version(String templateId, String version) {
        return versions(templateId).stream()
                .filter(record -> record.version().equals(version))
                .findFirst();
    }

    public Optional<TemplateRecord> active(String templateId) {
        String version = activeVersion.get(templateId);
        return version == null ? Optional.empty() : version(templateId, version);
    }

    public Optional<TemplateRecord> byExperimentTypeId(String experimentTypeId) {
        return byTemplateId.values().stream()
                .flatMap(List::stream)
                .filter(record -> record.status() == TemplateStatus.ACTIVE)
                .filter(record -> record.experimentTypeId().equals(experimentTypeId))
                .findFirst();
    }

    public Map<String, String> activeVersions() {
        return new LinkedHashMap<>(activeVersion);
    }

    private TemplateRecord flip(TemplateRecord record, TemplateStatus status) {
        return new TemplateRecord(record.templateId(), record.experimentTypeId(), record.displayName(),
                record.version(), record.entryPoint(), record.description(), record.source(), status,
                record.classification(), record.operationalValidated(), record.algorithmValidated(),
                record.createdAt(), record.publishedAt(), record.definitionSha256(), record.templateSha256(),
                record.activeVersion(), record.supportedParameters(), record.outputArtifacts());
    }
}
