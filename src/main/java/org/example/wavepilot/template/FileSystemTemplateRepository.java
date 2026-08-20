package org.example.wavepilot.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-system persistence for the formal registry: records live in
 * {@code registry/registry.json}, template files in {@code approved/<templateId>/<version>/}.
 * Writes use a temp file plus atomic move; records are re-read on reload so published
 * templates survive restarts. Absolute paths are never stored.
 */
@Repository
public class FileSystemTemplateRepository implements TemplateRepository {

    private final Path root;
    private final Path approvedDir;
    private final Path registryFile;
    private final ObjectMapper objectMapper;

    public FileSystemTemplateRepository(TemplateRootProperties properties, ObjectMapper objectMapper) {
        this.root = Path.of(properties.root()).toAbsolutePath().normalize();
        this.approvedDir = root.resolve("approved");
        this.registryFile = root.resolve("registry").resolve("registry.json");
        this.objectMapper = objectMapper;
        try {
            Files.createDirectories(approvedDir);
            Files.createDirectories(registryFile.getParent());
        } catch (IOException e) {
            throw new TemplateStorageException("Cannot initialize template registry directory", e);
        }
    }

    public Path approvedDir() {
        return approvedDir;
    }

    public Path approvedDirectory(String templateId, String version) {
        validateSegment(templateId, "templateId");
        validateSegment(version, "version");
        return approvedDir.resolve(templateId).resolve(version).normalize();
    }

    private void validateSegment(String segment, String what) {
        if (segment == null || segment.isBlank() || segment.contains("..") || segment.contains("/")
                || segment.contains("\\") || segment.startsWith(".") || segment.contains(":")) {
            throw new TemplateStorageException("Unsafe " + what + " path segment: " + segment);
        }
    }

    public Path root() {
        return root;
    }

    @Override
    public synchronized List<TemplateRecord> loadAll() {
        if (!Files.isRegularFile(registryFile)) {
            return List.of();
        }
        try {
            byte[] bytes = Files.readAllBytes(registryFile);
            if (bytes.length == 0) return List.of();
            List<TemplateRecord> records = objectMapper.readValue(bytes,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, TemplateRecord.class));
            return records == null ? List.of() : records;
        } catch (IOException e) {
            throw new TemplateStorageException("Cannot load template registry", e);
        }
    }

    @Override
    public synchronized void save(TemplateRecord record) {
        List<TemplateRecord> records = new ArrayList<>(loadAll());
        records.removeIf(existing -> existing.templateId().equals(record.templateId())
                && existing.version().equals(record.version()));
        records.add(record);
        writeAtomic(registryFile, toJson(records));
    }

    /** Writes template files into the approved directory atomically (temp dir + move). */
    public void writeApprovedFiles(String templateId, String version,
                                   List<TemplateFile> files) {
        Path target = approvedDirectory(templateId, version);
        Path temp = approvedDir.resolve(templateId).resolve(".tmp-" + version + "-" + Math.abs(version.hashCode()));
        try {
            Files.createDirectories(temp);
            for (TemplateFile file : files) {
                Path destination = temp.resolve(file.relativePath()).normalize();
                if (!destination.startsWith(temp)) {
                    throw new TemplateStorageException("Template file escapes the approved directory: "
                            + file.relativePath());
                }
                Files.createDirectories(destination.getParent());
                Files.write(destination, file.content(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }
            if (Files.exists(target)) {
                throw new TemplateStorageException("Version already exists and is immutable: "
                        + templateId + "/" + version);
            }
            Files.createDirectories(target.getParent());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new TemplateStorageException("Cannot publish template files " + templateId + "/" + version, e);
        } finally {
            deleteRecursively(temp);
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of the temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of the temp directory
        }
    }

    private void writeAtomic(Path target, byte[] content) {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(temp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // keep the original failure
            }
            throw new TemplateStorageException("Cannot write template registry atomically", e);
        }
    }

    private byte[] toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        } catch (IOException e) {
            throw new TemplateStorageException("Cannot serialize template registry", e);
        }
    }

    public record TemplateFile(String relativePath, byte[] content) { }

    public static class TemplateStorageException extends RuntimeException {
        public TemplateStorageException(String message) { super(message); }
        public TemplateStorageException(String message, Throwable cause) { super(message, cause); }
    }
}
