package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench must call every platform API the frontend promises. */
class FrontendApiContractTest {

    @Test
    void workbenchCallsTheConversationAndKnowledgeApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("'/wavepilot/chat'"), "chat API missing");
        assertTrue(app.contains("/wavepilot/knowledge/upload"), "knowledge upload API missing");
    }

    @Test
    void knowledgeUploadCarriesTheRequiredMetadata() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("form.append('documentType'"), "documentType param missing");
        assertTrue(app.contains("form.append('experimentType'"), "experimentType param missing");
        assertTrue(app.contains("form.append('title'"), "title param missing");
        assertTrue(app.contains("form.append('source'"), "source param missing");
        assertTrue(app.contains("form.append('version'"), "version param missing");
    }

    @Test
    void chatRepliesRenderTheAnswerFieldInsteadOfTheRawJson() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("reply.answer"), "chat reply must be read from the answer field");
        assertTrue(app.contains("mockRunner"), "mock/real mode must be surfaced in chat replies");
        assertFalse(app.contains("JSON.stringify(reply)"),
                "the raw chat JSON must never be dumped to the user");
    }

    @Test
    void workbenchCallsTheSpecAndJobApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/experiments/spec/parse"), "spec validation API missing");
        assertTrue(app.contains("'/experiments'"), "job creation API missing");
        assertTrue(app.contains("/progress'"), "progress API missing");
        assertTrue(app.contains("/cancel"), "cancel API missing");
        assertTrue(app.contains("/artifacts"), "artifacts API missing");
    }

    @Test
    void workbenchCallsTheReportAndCitationApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/report'"), "report generation API missing");
        assertTrue(app.contains("/report/validate"), "report validation API missing");
        assertTrue(app.contains("/citations'"), "citations API missing");
    }

    @Test
    void workbenchCallsTheReplayApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/replay'"), "replay start API missing");
        assertTrue(app.contains("'/replays/'"), "replay status API missing");
        assertTrue(app.contains("/comparison'"), "replay comparison API missing");
    }

    @Test
    void workbenchCallsTheEvaluationApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/evaluations/run"), "eval run API missing");
        assertTrue(app.contains("/evaluations/compare"), "eval compare API missing");
    }

    @Test
    void workbenchCallsThePathSafeArtifactDownloadAndVerifyApis() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/verify'"), "artifact verify API missing");
        assertTrue(app.contains("/download"), "artifact download API missing");
        assertTrue(!app.contains("C:\\") && !app.contains("D:\\"),
                "the frontend must never expose local absolute paths");
    }

    @Test
    void everyButtonOfTheWorkbenchLayoutIsWiredInJavascript() {
        String html = FrontendTestSupport.indexHtml();
        String app = FrontendTestSupport.appJs();
        List<String> buttonIds = List.of("chatSendBtn", "knowledgeUploadBtn", "validateSpecBtn",
                "createJobBtn", "cancelJobBtn", "refreshJobsBtn", "generateReportBtn",
                "validateReportBtn", "replayBtn", "refreshReplaysBtn", "evalRunBtn",
                "evalRunRegressedBtn", "evalCompareBtn");
        for (String id : buttonIds) {
            assertTrue(html.contains("id=\"" + id + "\""), "missing button " + id + " in index.html");
            assertTrue(app.contains("'" + id + "'"), "unwired button " + id + " in app.js");
        }
    }
}
