package org.example.wavepilot.scientific.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.scientific.model.AgentRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Single-process durable checkpoints; the interface can be replaced by JDBC without changing the loop. */
@Repository
public class FileAgentRunRepository implements AgentRunRepository {
    private static final Pattern SAFE_ID = Pattern.compile("RUN-[A-Z0-9]{8}-[A-Z0-9]{3}");
    private final Path root;
    private final ObjectMapper objectMapper;

    public FileAgentRunRepository(@Value("${wavepilot.scientific.run-store:data/wavepilot/agent-runs}") String root,
                                  ObjectMapper objectMapper) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
    }

    @Override
    public synchronized AgentRun save(AgentRun run) {
        requireSafe(run == null ? null : run.getRunId());
        try {
            Files.createDirectories(root);
            Path target = target(run.getRunId());
            Path temporary = root.resolve(run.getRunId() + ".json.tmp").normalize();
            ensureWithin(temporary);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), run);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return run;
        } catch (IOException e) {
            throw new IllegalStateException("Could not checkpoint AgentRun " + run.getRunId(), e);
        }
    }

    @Override
    public synchronized Optional<AgentRun> findById(String runId) {
        requireSafe(runId);
        Path target = target(runId);
        if (!Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(target.toFile(), AgentRun.class));
        } catch (IOException e) {
            throw new IllegalStateException("Could not load AgentRun checkpoint " + runId, e);
        }
    }

    @Override
    public synchronized List<AgentRun> findAll() {
        if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) {
            return files.filter(path -> path.getFileName().toString().matches("RUN-[A-Z0-9]{8}-[A-Z0-9]{3}\\.json"))
                    .map(path -> findById(path.getFileName().toString().replace(".json", "")).orElseThrow())
                    .sorted(Comparator.comparing(AgentRun::getCreatedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list AgentRun checkpoints", e);
        }
    }

    private Path target(String runId) {
        Path target = root.resolve(runId + ".json").normalize();
        ensureWithin(target);
        return target;
    }

    private void requireSafe(String runId) {
        if (runId == null || !SAFE_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException("Unsafe AgentRun id: " + runId);
        }
    }

    private void ensureWithin(Path path) {
        if (!path.startsWith(root)) throw new IllegalArgumentException("AgentRun path escapes store root");
    }
}
