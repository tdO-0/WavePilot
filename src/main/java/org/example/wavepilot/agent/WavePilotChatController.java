package org.example.wavepilot.agent;

import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/wavepilot/chat")
@ConditionalOnProperty(prefix = "wavepilot", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WavePilotChatController {

    private final WavePilotChatService chatService;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public WavePilotChatController(WavePilotChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping
    public WavePilotChatService.ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request.conversationId(), request.message());
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String conversationId,
                             @RequestParam String message,
                             HttpServletResponse response) {
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        SseEmitter emitter = new SseEmitter(300_000L);
        streamExecutor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("answer")
                        .data(chatService.chat(conversationId, message)));
                emitter.complete();
            } catch (IOException | RuntimeException e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PreDestroy
    public void shutdown() { streamExecutor.shutdownNow(); }

    public record ChatRequest(String conversationId, String message) { }
}
