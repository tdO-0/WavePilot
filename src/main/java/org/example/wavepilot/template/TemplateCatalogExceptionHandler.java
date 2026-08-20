package org.example.wavepilot.template;

import org.example.wavepilot.template.generation.TemplateGenerationService;
import org.example.wavepilot.template.publish.TemplatePublishingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice(assignableTypes = TemplateCatalogController.class)
public class TemplateCatalogExceptionHandler {

    @ExceptionHandler({TemplatePublishingService.PublishingException.class,
            TemplateGenerationService.TemplateGenerationException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(RuntimeException exception) {
        return ResponseEntity.unprocessableEntity().body(Map.of("code", "TEMPLATE_OPERATION_INVALID",
                "message", safe(exception)));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("code", "TEMPLATE_NOT_FOUND",
                "message", safe(exception)));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "TEMPLATE_STATE_INVALID",
                "message", safe(exception)));
    }

    private String safe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
