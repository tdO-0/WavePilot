package org.example.wavepilot.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

@Service
public class ArtifactRegistry {

    private static final Pattern SAFE_JOB_ID = Pattern.compile("[A-Za-z0-9_-]{1,100}");

    private final Path root;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, ArtifactRecord> records = new ConcurrentHashMap<>();
    @Value("${wavepilot.artifacts.shared-metadata:false}")
    private boolean sharedMetadata;

    public ArtifactRegistry(@Value("${wavepilot.artifacts.root:artifacts}") String root,
                            ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public Path createJobDirectory(String jobId) {
        validateJobId(jobId);
        Path jobDirectory = root.resolve(jobId).normalize();
        ensureWithin(jobDirectory, root);
        try {
            Files.createDirectories(jobDirectory);
            return jobDirectory;
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot create artifact directory for job " + jobId, e);
        }
    }

    public ArtifactRecord writeJson(String jobId, ArtifactType type, String fileName, Object value) {
        Path target = resolveForWrite(jobId, fileName);
        try {
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            Files.write(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return register(jobId, type, target);
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot write JSON artifact " + fileName, e);
        }
    }

    public ArtifactRecord register(String jobId, ArtifactType type, Path file) {
        Path jobDirectory = createJobDirectory(jobId);
        try {
            Path realJobDirectory = jobDirectory.toRealPath();
            if (Files.isSymbolicLink(file)) {
                throw new ArtifactStorageException("Symbolic links are not allowed as artifacts");
            }
            Path realFile = file.toAbsolutePath().normalize().toRealPath(LinkOption.NOFOLLOW_LINKS);
            ensureWithin(realFile, realJobDirectory);
            if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactStorageException("Artifact is not a regular file: " + realFile);
            }
            ArtifactRecord record = new ArtifactRecord(
                    "ART-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    jobId,
                    type,
                    realFile.getFileName().toString(),
                    "unknown",
                    true,
                    false,
                    "UNCLASSIFIED",
                    root.relativize(realFile).toString().replace('\\', '/'),
                    sha256(realFile),
                    Files.size(realFile),
                    mimeType(realFile),
                    "unknown",
                    "unknown",
                    false,
                    Instant.now(),
                    realFile.toString());
            records.put(record.artifactId(), record);
            persistMetadata(record);
            return record;
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot register artifact " + file, e);
        }
    }

    public List<ArtifactRecord> listByJobId(String jobId) {
        validateJobId(jobId);
        if (sharedMetadata) loadMetadata();
        return records.values().stream()
                .filter(record -> record.jobId().equals(jobId))
                .sorted((left, right) -> left.createdAt().compareTo(right.createdAt()))
                .toList();
    }

    public Optional<ArtifactRecord> findById(String artifactId) {
        if (sharedMetadata) loadMetadata();
        return Optional.ofNullable(records.get(artifactId));
    }

    public void markJobValidated(String jobId, String runnerType, boolean mock,
                                 boolean algorithmValidated, String classification,
                                 String templateVersion, String algorithmVersion) {
        validateJobId(jobId);
        if (sharedMetadata) loadMetadata();
        records.replaceAll((id, record) -> record.jobId().equals(jobId)
                ? new ArtifactRecord(record.artifactId(), record.jobId(), record.type(), record.fileName(),
                runnerType, mock, algorithmValidated, classification, record.relativePath(),
                record.sha256(), record.size(), record.mimeType(), templateVersion, algorithmVersion,
                true, record.createdAt(), record.path())
                : record);
        if (sharedMetadata) records.values().stream().filter(record -> record.jobId().equals(jobId))
                .forEach(this::persistMetadata);
    }

    /** Small shared-volume registry for the optional API/Worker deployment. One file per record
     * avoids a read/modify/write manifest race between different processes. */
    private void persistMetadata(ArtifactRecord record) {
        if (!sharedMetadata) return;
        try {
            Path directory = root.resolve(".metadata");
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "artifact-", ".tmp");
            try {
                objectMapper.writeValue(temporary.toFile(), record);
                Path target = directory.resolve(record.artifactId() + ".json");
                try {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot persist shared artifact metadata", e);
        }
    }

    private void loadMetadata() {
        Path directory = root.resolve(".metadata");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                if (Files.isSymbolicLink(file)) throw new ArtifactStorageException("Metadata symlink rejected");
                ArtifactRecord record = objectMapper.readValue(file.toFile(), ArtifactRecord.class);
                validateJobId(record.jobId());
                Path resolved = root.resolve(record.relativePath()).normalize();
                ensureWithin(resolved, root.resolve(record.jobId()));
                // Do not trust a different process's absolute path.
                records.put(record.artifactId(), new ArtifactRecord(record.artifactId(), record.jobId(),
                        record.type(), record.fileName(), record.runnerType(), record.mock(), record.algorithmValidated(),
                        record.classification(), record.relativePath(), record.sha256(), record.size(), record.mimeType(),
                        record.templateVersion(), record.algorithmVersion(), record.validated(), record.createdAt(), resolved.toString()));
            }
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot read shared artifact metadata", e);
        }
    }

    public boolean verify(String artifactId) {
        ArtifactRecord record = findById(artifactId)
                .orElseThrow(() -> new ArtifactStorageException("Artifact not found: " + artifactId));
        Path file = resolveVerified(record);
        try {
            return Files.size(file) == record.size() && sha256(file).equals(record.sha256());
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot verify artifact " + artifactId, e);
        }
    }

    public Path resolveVerified(String artifactId) {
        return resolveVerified(findById(artifactId)
                .orElseThrow(() -> new ArtifactStorageException("Artifact not found: " + artifactId)));
    }

    /** Verify a durable-ledger reference without trusting the in-memory registry. */
    public Path resolveVerifiedReference(String relativePath, String expectedSha256, long expectedSize) {
        if (relativePath == null || relativePath.isBlank() || Path.of(relativePath).isAbsolute()) {
            throw new ArtifactStorageException("Ledger artifact path must be relative");
        }
        try {
            Path candidate = root.resolve(relativePath).normalize();
            ensureWithin(candidate, root);
            if (Files.isSymbolicLink(candidate)) {
                throw new ArtifactStorageException("Symbolic links are not allowed as artifacts");
            }
            Path realFile = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            ensureWithin(realFile, root);
            if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(realFile) != expectedSize || !sha256(realFile).equals(expectedSha256)) {
                throw new ArtifactStorageException("Ledger artifact hash/size verification failed: " + relativePath);
            }
            return realFile;
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot verify ledger artifact " + relativePath, e);
        }
    }

    private Path resolveVerified(ArtifactRecord record) {
        validateJobId(record.jobId());
        try {
            Path jobDirectory = root.resolve(record.jobId()).normalize().toRealPath();
            ensureWithin(jobDirectory, root);
            Path candidate = root.resolve(record.relativePath()).normalize();
            ensureWithin(candidate, jobDirectory);
            if (Files.isSymbolicLink(candidate)) {
                throw new ArtifactStorageException("Symbolic links are not allowed as artifacts");
            }
            Path realFile = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            ensureWithin(realFile, jobDirectory);
            if (!Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new ArtifactStorageException("Artifact is not a regular file: " + record.artifactId());
            }
            return realFile;
        } catch (IOException e) {
            throw new ArtifactStorageException("Cannot resolve artifact " + record.artifactId(), e);
        }
    }

    public Path getRoot() {
        return root;
    }

    private Path resolveForWrite(String jobId, String fileName) {
        if (fileName == null || fileName.isBlank() || !Path.of(fileName).getFileName().toString().equals(fileName)) {
            throw new ArtifactStorageException("Artifact file name must not contain a path: " + fileName);
        }
        Path jobDirectory = createJobDirectory(jobId);
        Path target = jobDirectory.resolve(fileName).normalize();
        ensureWithin(target, jobDirectory);
        return target;
    }

    private void validateJobId(String jobId) {
        if (jobId == null || !SAFE_JOB_ID.matcher(jobId).matches()) {
            throw new ArtifactStorageException("Unsafe jobId: " + jobId);
        }
    }

    private void ensureWithin(Path candidate, Path expectedParent) {
        if (!candidate.startsWith(expectedParent.toAbsolutePath().normalize())) {
            throw new ArtifactStorageException("Artifact path escapes its job directory: " + candidate);
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String mimeType(Path file) throws IOException {
        String detected = Files.probeContentType(file);
        if (detected != null) return detected;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".mat")) return "application/x-matlab-data";
        if (name.endsWith(".md")) return "text/markdown";
        return "application/octet-stream";
    }

    public static class ArtifactStorageException extends RuntimeException {
        public ArtifactStorageException(String message) { super(message); }
        public ArtifactStorageException(String message, Throwable cause) { super(message, cause); }
    }
}
