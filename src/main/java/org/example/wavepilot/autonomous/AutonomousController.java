package org.example.wavepilot.autonomous;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/autonomous")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AutonomousController {

    private final AutonomousSessionService sessions;

    public AutonomousController(AutonomousSessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping("/start")
    public AutonomousSession start(@RequestBody Map<String, Object> body) {
        boolean analyzeResults = Boolean.parseBoolean(
                String.valueOf(body.getOrDefault("analyzeResults", false)));
        return sessions.start(String.valueOf(body.get("request")), analyzeResults);
    }

    @GetMapping
    public List<AutonomousSession> list() {
        return sessions.list();
    }

    @GetMapping("/{sessionId}")
    public AutonomousSession get(@PathVariable String sessionId) {
        return sessions.get(sessionId);
    }

    @PostMapping("/{sessionId}/params")
    @SuppressWarnings("unchecked")
    public AutonomousSession submitParams(@PathVariable String sessionId,
                                          @RequestBody Map<String, Object> body) {
        Map<String, Object> params = (Map<String, Object>) body.getOrDefault("params", Map.of());
        return sessions.submitParams(sessionId, params);
    }

    @PostMapping("/{sessionId}/approval")
    public AutonomousSession submitApproval(@PathVariable String sessionId,
                                            @RequestBody Map<String, String> body) {
        boolean approved = Boolean.parseBoolean(body.getOrDefault("approved", "false"));
        return sessions.submitApproval(sessionId, approved, body.get("approvedBy"));
    }

    @PostMapping("/{sessionId}/cancel")
    public AutonomousSession cancel(@PathVariable String sessionId) {
        return sessions.cancel(sessionId);
    }
}
