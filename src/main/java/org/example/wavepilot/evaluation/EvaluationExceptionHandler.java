package org.example.wavepilot.evaluation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = EvaluationController.class)
public class EvaluationExceptionHandler {

    @ExceptionHandler(EvaluationException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(EvaluationException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "EVALUATION_INVALID",
                "message", safe(exception)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "EVALUATION_NOT_FOUND",
                "message", safe(exception)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "EVALUATION_NOT_READY",
                "message", safe(exception)));
    }

    private String safe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
