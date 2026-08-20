package org.example.wavepilot.report;

import org.example.wavepilot.experiment.model.ValidationResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = ReportController.class)
public class ReportExceptionHandler {

    @ExceptionHandler(ReportService.ReportValidationException.class)
    public ResponseEntity<ValidationResult> invalidCitation(ReportService.ReportValidationException exception) {
        return ResponseEntity.unprocessableEntity().body(exception.getValidationResult());
    }

    @ExceptionHandler(ReportDataAssembler.ReportDataException.class)
    public ResponseEntity<Map<String, String>> invalidData(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "REPORT_DATA_INVALID",
                "message", safe(exception)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "REPORT_NOT_READY",
                "message", safe(exception)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "REPORT_NOT_FOUND",
                "message", safe(exception)));
    }

    private String safe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
