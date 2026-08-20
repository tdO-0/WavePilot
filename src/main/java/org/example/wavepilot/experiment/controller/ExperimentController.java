package org.example.wavepilot.experiment.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.wavepilot.artifact.ArtifactRecord;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentProgress;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/experiments")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExperimentController {

    private static final long SSE_TIMEOUT_MILLIS = 10 * 60 * 1000L;
    private final ExperimentService experimentService;

    public ExperimentController(ExperimentService experimentService) {
        this.experimentService = experimentService;
    }

    /** Phase 0-2 accepts structured JSON; natural-language parsing belongs to Phase 3. */
    @PostMapping("/spec/parse")
    public ValidationResult parseSpec(@RequestBody ExperimentSpec spec) {
        return experimentService.parseAndValidate(spec);
    }

    @PostMapping
    public ResponseEntity<ExperimentJob> create(@RequestBody ExperimentSpec spec) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(experimentService.create(spec));
    }

    @GetMapping
    public List<ExperimentJob> list() { return experimentService.list(); }

    @GetMapping("/{jobId}")
    public ExperimentJob get(@PathVariable String jobId) { return experimentService.get(jobId); }

    @GetMapping("/{jobId}/progress")
    public ExperimentProgress progress(@PathVariable String jobId) { return experimentService.progress(jobId); }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String jobId, HttpServletResponse response) {
        // Job existence is confirmed BEFORE the SSE connection is established. A missing
        // job must not fall through to the JSON exception handler: the client accepts
        // text/event-stream, so a JSON error response would raise a second
        // HttpMediaTypeNotAcceptableException on top of the original 404. Instead the
        // failure is delivered as an SSE event so the client can close the connection,
        // stop auto-reconnect and clear the stale job state.
        try {
            experimentService.get(jobId);
        } catch (NoSuchElementException e) {
            return jobNotFoundEmitter(jobId);
        }
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        CompletableFuture.runAsync(() -> emitProgress(jobId, emitter));
        return emitter;
    }

    private SseEmitter jobNotFoundEmitter(String jobId) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name("job-not-found")
                    .data("{\"jobId\":\"" + jobId + "\"}"));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }
        emitter.complete();
        return emitter;
    }

    @PostMapping("/{jobId}/cancel")
    public ExperimentJob cancel(@PathVariable String jobId) { return experimentService.cancel(jobId); }

    @GetMapping("/{jobId}/artifacts")
    public List<ArtifactRecord> artifacts(@PathVariable String jobId) {
        return experimentService.artifacts(jobId);
    }

    private void emitProgress(String jobId, SseEmitter emitter) {
        ExperimentProgress previous = null;
        try {
            while (true) {
                ExperimentProgress current = experimentService.progress(jobId);
                if (!current.equals(previous)) {
                    emitter.send(SseEmitter.event().name("progress")
                            .id(current.timestamp().toString()).data(current));
                    previous = current;
                }
                if (current.status().isTerminal()) {
                    emitter.complete();
                    return;
                }
                Thread.sleep(200);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emitter.completeWithError(e);
        } catch (IOException | RuntimeException e) {
            emitter.completeWithError(e);
        }
    }
}
