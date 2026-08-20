package org.example.wavepilot.knowledge;

import org.example.wavepilot.config.KnowledgeUploadProperties;
import org.example.wavepilot.experiment.model.ExperimentType;
import org.example.wavepilot.knowledge.model.DocumentType;
import org.example.wavepilot.knowledge.model.KnowledgeSearchRequest;
import org.example.wavepilot.knowledge.model.KnowledgeSearchResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/wavepilot/knowledge")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeUploadProperties uploadConfig;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeUploadProperties uploadConfig) {
        this.knowledgeService = knowledgeService;
        this.uploadConfig = uploadConfig;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KnowledgeService.KnowledgeIngestResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam DocumentType documentType,
            @RequestParam ExperimentType experimentType,
            @RequestParam String title,
            @RequestParam String source,
            @RequestParam String version) throws IOException {
        validateFile(file);
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok(knowledgeService.ingest(content, documentType, experimentType,
                title, source, version));
    }

    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgeSearchResult> search(@RequestBody KnowledgeSearchRequest request) {
        return knowledgeService.search(request);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Knowledge file is required");
        if (file.getSize() > uploadConfig.getMaxSizeBytes()) {
            throw new IllegalArgumentException("Knowledge file exceeds " + uploadConfig.getMaxSizeBytes() + " bytes");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) throw new IllegalArgumentException("Knowledge file name is required");
        String safe = Path.of(original).getFileName().toString();
        if (!safe.equals(original) || safe.contains("/") || safe.contains("\\")) {
            throw new IllegalArgumentException("Knowledge file name contains a path");
        }
        int dot = safe.lastIndexOf('.');
        String extension = dot < 0 ? "" : safe.substring(dot + 1).toLowerCase();
        List<String> allowed = Arrays.stream(uploadConfig.getAllowedExtensions().split(","))
                .map(String::trim).map(String::toLowerCase).toList();
        if (!allowed.contains(extension)) throw new IllegalArgumentException("Unsupported knowledge file extension");
    }
}
