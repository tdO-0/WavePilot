package org.example.wavepilot.artifact;

import org.example.wavepilot.experiment.model.ValidationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/artifacts")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ArtifactController {

    private final ArtifactRegistry registry;

    public ArtifactController(ArtifactRegistry registry) { this.registry = registry; }

    @GetMapping("/{artifactId}/metadata")
    public ArtifactRecord metadata(@PathVariable String artifactId) {
        return registry.findById(artifactId)
                .orElseThrow(() -> new NoSuchElementException("Artifact not found: " + artifactId));
    }

    @GetMapping("/{artifactId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable String artifactId) throws IOException {
        ArtifactRecord record = metadata(artifactId);
        Path file = registry.resolveVerified(artifactId);
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(record.mimeType()); }
        catch (IllegalArgumentException ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(record.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(Files.newInputStream(file)));
    }

    @PostMapping("/{artifactId}/verify")
    public ValidationResult verify(@PathVariable String artifactId) {
        metadata(artifactId);
        return registry.verify(artifactId)
                ? ValidationResult.success(List.of())
                : ValidationResult.failure(List.of("Artifact SHA-256 or size does not match ArtifactRecord"), List.of());
    }
}
