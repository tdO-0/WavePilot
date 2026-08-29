package org.example.wavepilot.scientific.service;

import org.example.wavepilot.scientific.model.AgentRun;
import org.example.wavepilot.scientific.model.ExperimentGoal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/scientific-agent/runs")
public class ScientificAgentController {
    private final ScientificAgentService service;

    public ScientificAgentController(ScientificAgentService service) { this.service = service; }

    @PostMapping
    public AgentRun start(@RequestBody ExperimentGoal goal) { return service.start(goal); }

    @PostMapping("/checkpoints")
    public AgentRun checkpoint(@RequestBody ExperimentGoal goal) { return service.createCheckpoint(goal); }

    @PostMapping("/{runId}/resume")
    public AgentRun resume(@PathVariable String runId) { return service.resume(runId); }

    @GetMapping("/{runId}")
    public AgentRun get(@PathVariable String runId) { return service.get(runId); }

    @GetMapping
    public List<AgentRun> list() { return service.list(); }
}
