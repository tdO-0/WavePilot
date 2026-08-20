package org.example.wavepilot.artifact;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = ArtifactController.class)
public class ArtifactExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(ArtifactRegistry.ArtifactStorageException.class)
    public ResponseEntity<Map<String, String>> unsafe(ArtifactRegistry.ArtifactStorageException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", exception.getMessage()));
    }
}
