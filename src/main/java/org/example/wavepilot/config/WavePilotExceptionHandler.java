package org.example.wavepilot.config;

import org.example.wavepilot.agent.WavePilotChatController;
import org.example.wavepilot.agent.spec.ExperimentSpecParseController;
import org.example.wavepilot.knowledge.KnowledgeController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = {
        WavePilotChatController.class,
        ExperimentSpecParseController.class,
        KnowledgeController.class
})
public class WavePilotExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "code", "INVALID_WAVEPILOT_REQUEST",
                "message", safeMessage(exception)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "code", "WAVEPILOT_DEPENDENCY_UNAVAILABLE",
                "message", safeMessage(exception)));
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
