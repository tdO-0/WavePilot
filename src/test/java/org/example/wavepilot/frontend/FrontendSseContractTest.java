package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSE progress must be real-time and must never lie about terminal states. */
class FrontendSseContractTest {

    @Test
    void workbenchConnectsToTheSseStreamWithEventSource() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("new EventSource"), "SSE must use EventSource");
        assertTrue(app.contains("/stream'"), "SSE endpoint missing");
        assertTrue(app.contains("addEventListener('progress'"), "progress frames must be consumed");
    }

    @Test
    void sseDisconnectsAreSurfacedAndNeverTurnedIntoSucceeded() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("onerror"), "SSE error handling missing");
        assertTrue(app.contains("自动重连"), "SSE reconnect hint missing");
        // The status line always mirrors the server value through the label helper; there is
        // no client-side mapping that would render RUNNING as SUCCEEDED.
        assertTrue(app.contains("jobStatusLine.textContent = '状态：' + statusLabel(progress.status)"));
        assertTrue(app.contains("STATUS_LABELS"), "status labels must be centralized");
        assertFalse(app.contains("replace('RUNNING', 'SUCCEEDED')")
                        || app.contains("replaceAll('RUNNING', 'SUCCEEDED')")
                        || app.contains("'RUNNING': 'SUCCEEDED'"),
                "no RUNNING-to-SUCCEEDED text replacement may exist");
    }

    @Test
    void progressAndCurrentParameterPointAreAlwaysShown() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("progressBar.style.width"), "progress bar must update");
        assertTrue(app.contains("当前参数点"), "current parameter point must be displayed");
        assertTrue(app.contains("stageLabel(progress.currentStage)"),
                "the frontend must use the ExperimentProgress currentStage field");
        assertFalse(app.contains("stageLabel(progress.stage)"),
                "the nonexistent stage field would render undefined");
        assertTrue(app.contains("SUCCEEDED: '成功'"),
                "terminal stages should remain readable while retaining the enum value");
    }

    @Test
    void theUiNeverPrecedesTheServerInDeclaringSuccess() {
        // Success may only ever come from the server-provided status value.
        String app = FrontendTestSupport.appJs();
        assertFalse(app.contains("status = 'SUCCEEDED'") || app.contains("status==='SUCCEEDED'"),
                "the frontend must not fabricate SUCCEEDED");
    }

    @Test
    void terminalProgressRefreshesArtifactsAndResultNavigationDoesNotUseStaleCache() {
        String app = FrontendTestSupport.appJs();
        String html = FrontendTestSupport.indexHtml();
        assertTrue(app.contains("['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(progress.status)"),
                "terminal SSE progress must trigger final-state handling");
        assertTrue(app.contains("this.loadArtifacts(jobId)"),
                "terminal handling must refresh the selected job artifacts");
        assertTrue(html.contains("window.workbench.loadArtifacts(window.workbench.selectedJobId).then(syncContext)"),
                "opening the results page must fetch current artifacts instead of displaying an early cache");
    }
}
