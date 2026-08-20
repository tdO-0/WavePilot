package org.example.wavepilot.agent.spec;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wavepilot/spec")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ExperimentSpecParseController {

    private final ExperimentSpecParser parser;

    public ExperimentSpecParseController(ExperimentSpecParser parser) {
        this.parser = parser;
    }

    @PostMapping("/parse")
    public ExperimentSpecParseResult parse(@RequestBody ParseRequest request) {
        return parser.parse(request == null ? null : request.message());
    }

    public record ParseRequest(String message) { }
}
