package org.example.wavepilot.replay;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = ReplayController.class)
public class ReplayExceptionHandler {

    @ExceptionHandler(ReplayService.ReplayValidationException.class)
    public ResponseEntity<Map<String, String>> invalidSource(ReplayService.ReplayValidationException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "REPLAY_SOURCE_INVALID",
                "message", safe(exception)));
    }

    @ExceptionHandler(ReplayComparisonEvaluator.ReplayComparisonException.class)
    public ResponseEntity<Map<String, String>> invalidComparison(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "REPLAY_COMPARISON_INVALID",
                "message", safe(exception)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "REPLAY_NOT_FOUND",
                "message", safe(exception)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "REPLAY_NOT_READY",
                "message", safe(exception)));
    }

    private String safe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
