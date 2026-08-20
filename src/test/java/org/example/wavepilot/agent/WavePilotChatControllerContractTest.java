package org.example.wavepilot.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WavePilotChatController.class)
class WavePilotChatControllerContractTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    WavePilotChatService chatService;

    @Test
    void exposesIndependentWavePilotChatEndpoint() throws Exception {
        when(chatService.chat("CONV-1", "run polar experiment"))
                .thenReturn(new WavePilotChatService.ChatResponse("CONV-1", "Mock response", true, null, null));

        mockMvc.perform(post("/api/wavepilot/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"CONV-1\",\"message\":\"run polar experiment\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mockRunner").value(true))
                .andExpect(jsonPath("$.answer").value("Mock response"));
    }

    @Test
    void exposesSseEndpointWithoutUsingLegacyChatController() throws Exception {
        when(chatService.chat("CONV-2", "status"))
                .thenReturn(new WavePilotChatService.ChatResponse("CONV-2", "Mock status", true, null, null));

        mockMvc.perform(get("/api/wavepilot/chat/stream")
                        .param("conversationId", "CONV-2").param("message", "status"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }
}
