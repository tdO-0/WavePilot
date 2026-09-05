package org.example.wavepilot.experiment.controller;

import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.service.ExperimentService.InvalidExperimentSpecException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = ExperimentController.class)
public class ExperimentExceptionHandler {

    @ExceptionHandler(org.example.wavepilot.experiment.service.ExperimentService.DispatchUnavailableException.class)
    public ResponseEntity<Map<String, String>> publishUnavailable(
            org.example.wavepilot.experiment.service.ExperimentService.DispatchUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("jobId", exception.getJobId(), "error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(InvalidExperimentSpecException.class)
    public ResponseEntity<ValidationResult> invalidSpec(InvalidExperimentSpecException exception) {
        return ResponseEntity.unprocessableEntity().body(exception.getValidationResult());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, org.springframework.dao.OptimisticLockingFailureException.class})
    public ResponseEntity<Map<String, String>> stateConflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }
}
