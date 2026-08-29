package org.example.wavepilot.scientific.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Atomic single-node execution ledger. The interface remains replaceable by JDBC. */
@Repository
public class FileExecutionLedgerRepository implements ExecutionLedgerRepository {
    private static final Pattern SAFE_ID = Pattern.compile("EXEC-[A-Z0-9_-]{6,80}");
    private final Path root;
    private final ObjectMapper objectMapper;

    public FileExecutionLedgerRepository(
            @Value("${wavepilot.scientific.execution-ledger-store:data/wavepilot/execution-ledger}") String root,
            ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public synchronized ExecutionLedgerEntry save(ExecutionLedgerEntry entry) {
        requireSafe(entry == null ? null : entry.executionId());
        try {
            Files.createDirectories(root);
            Path target = target(entry.executionId());
            Path temporary = root.resolve(entry.executionId() + ".json.tmp").normalize();
            ensureWithin(temporary);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), entry);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return entry;
        } catch (IOException e) {
            throw new IllegalStateException("Could not persist execution ledger " + entry.executionId(), e);
        }
    }

    @Override
    public synchronized Optional<ExecutionLedgerEntry> findByExecutionId(String executionId) {
        requireSafe(executionId);
        Path target = target(executionId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), ExecutionLedgerEntry.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read execution ledger " + executionId, e);
        }
    }

    @Override
    public synchronized List<ExecutionLedgerEntry> findByRunId(String runId) {
        if (!Files.isDirectory(root)) return List.of();
        try (var paths = Files.list(root)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(path -> path.getFileName().toString().replace(".json", ""))
                    .map(this::findByExecutionId).flatMap(Optional::stream)
                    .filter(entry -> runId.equals(entry.runId())).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list execution ledger", e);
        }
    }

    private Path target(String executionId) {
        Path value = root.resolve(executionId + ".json").normalize();
        ensureWithin(value);
        return value;
    }

    private void requireSafe(String executionId) {
        if (executionId == null || !SAFE_ID.matcher(executionId).matches()) {
            throw new IllegalArgumentException("Unsafe executionId: " + executionId);
        }
    }

    private void ensureWithin(Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("Execution ledger path escapes root");
    }
}
