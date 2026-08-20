package org.example.wavepilot.experiment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.wavepilot.WavePilotTestFixtures;
import org.example.wavepilot.experiment.model.ExperimentJob;
import org.example.wavepilot.experiment.model.ExperimentSpec;
import org.example.wavepilot.experiment.model.ExperimentStatus;
import org.example.wavepilot.experiment.model.ValidationResult;
import org.example.wavepilot.experiment.service.ExperimentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExperimentController.class)
class ExperimentControllerContractTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    ExperimentService experimentService;

    @Test
    void validatesStructuredSpecAndCreatesAsyncJob() throws Exception {
        ExperimentJob job = WavePilotTestFixtures.job("JOB-API-1");
        when(experimentService.parseAndValidate(any())).thenReturn(ValidationResult.success(List.of()));
        when(experimentService.create(any(ExperimentSpec.class))).thenReturn(job);
        String request = objectMapper.writeValueAsString(WavePilotTestFixtures.validSpec());

        mockMvc.perform(post("/api/experiments/spec/parse")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mockMvc.perform(post("/api/experiments")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value("JOB-API-1"));
    }

    @Test
    void returnsNotFoundContract() throws Exception {
        when(experimentService.get("JOB-MISSING"))
                .thenThrow(new NoSuchElementException("Experiment job not found: JOB-MISSING"));

        mockMvc.perform(get("/api/experiments/JOB-MISSING"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Experiment job not found: JOB-MISSING"));
    }

    @Test
    void exposesSseProgressStream() throws Exception {
        ExperimentJob job = WavePilotTestFixtures.job("JOB-API-SSE");
        job.changeStatus(ExperimentStatus.SUCCEEDED, "done");
        when(experimentService.get("JOB-API-SSE")).thenReturn(job);
        when(experimentService.progress("JOB-API-SSE")).thenReturn(job.getProgress());

        mockMvc.perform(get("/api/experiments/JOB-API-SSE/stream"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }

    @Test
    void missingJobSseStreamEmitsJobNotFoundEventInsteadOfJsonError() throws Exception {
        // Regression: an SSE request for a missing job must NOT fall through to the JSON
        // exception handler (Accept: text/event-stream would raise a second
        // HttpMediaTypeNotAcceptableException). It must deliver an SSE "job-not-found"
        // event so the client closes the stream, stops auto-reconnect and clears the
        // stale job state.
        when(experimentService.get("JOB-GONE"))
                .thenThrow(new NoSuchElementException("Experiment job not found: JOB-GONE"));

        MvcResult asyncResult = mockMvc.perform(get("/api/experiments/JOB-GONE/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("job-not-found")));
    }
}
