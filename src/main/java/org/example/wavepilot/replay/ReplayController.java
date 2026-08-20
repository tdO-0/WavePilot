package org.example.wavepilot.replay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReplayController {

    private final ReplayService replayService;

    public ReplayController(ReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/experiments/{jobId}/replay")
    public ReplayRecord start(@PathVariable String jobId,
                              @RequestBody(required = false) ReplayRequest request) {
        return replayService.startReplay(jobId, request);
    }

    @GetMapping("/replays")
    public List<ReplayRecord> list() { return replayService.list(); }

    @GetMapping("/replays/{replayId}")
    public ReplayRecord get(@PathVariable String replayId) { return replayService.get(replayId); }

    @GetMapping("/replays/{replayId}/comparison")
    public ReplayComparisonResult comparison(@PathVariable String replayId) {
        return replayService.comparison(replayId);
    }

    @GetMapping("/replays/{replayId}/manifest")
    public ReplayManifest manifest(@PathVariable String replayId) { return replayService.manifest(replayId); }
}
