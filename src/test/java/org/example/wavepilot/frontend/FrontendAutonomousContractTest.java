package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench autonomous panel must expose the controlled loop without new bypasses. */
class FrontendAutonomousContractTest {

    @Test
    void theAutonomousPanelExistsInTheLayoutWithAToggle() {
        String html = FrontendTestSupport.indexHtml();
        assertTrue(html.contains("autonomousToggle"), "autonomous toggle missing");
        assertTrue(html.contains("autonomousPanel"), "autonomous panel missing");
        assertTrue(html.contains("autonomousTimeline"), "timeline container missing");
        assertTrue(html.contains("autonomousStartBtn"), "start button missing");
        assertTrue(html.contains("autonomousCancelBtn"), "cancel button missing");
    }

    @Test
    void thePanelCallsAllAutonomousEndpoints() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/autonomous/start"), "start endpoint missing");
        assertTrue(app.contains("/autonomous/' + this.sessionId"), "session polling endpoint missing");
        assertTrue(app.contains("/autonomous/' + sessionId + '/params"), "params endpoint missing");
        assertTrue(app.contains("/autonomous/' + this.sessionId + '/approval"), "approval endpoint missing");
        assertTrue(app.contains("/autonomous/' + this.sessionId + '/cancel"), "cancel endpoint missing");
    }

    @Test
    void thePanelPollsEverySecondAndStopsAtTerminalStates() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("setTimeout(tick, 1000)"), "the panel must poll on a 1s cadence");
        assertTrue(app.contains("startPolling"), "polling driver missing");
        assertTrue(app.contains("stopPolling"), "polling stop missing");
        assertTrue(app.contains("isTerminal"), "terminal-state check missing");
        assertTrue(app.contains("SUCCEEDED"), "success state handling missing");
    }

    @Test
    void waitingParamsReusesTheParameterDialog() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("openAutonomousParamDialog"), "params suspension must open the dialog");
        assertTrue(app.contains("WAITING_PARAMS"), "params suspension state missing");
        assertTrue(app.contains("submitParams"), "params submission missing");
        assertTrue(app.contains("params: params"), "params body must be the filled values");
    }

    @Test
    void parameterPromptsShowDescriptionsUnitsAndTheMatchedTemplateVersion() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("templateDisplayName"), "prompts must name the matched template");
        assertTrue(app.contains("pending.version"), "prompts must show the template version");
        assertTrue(app.contains("p.description"), "prompts must show parameter descriptions");
        assertTrue(app.contains("p.unit"), "prompts must show parameter units");
        assertTrue(app.contains("默认 "), "prompts must show default values");
        assertTrue(app.contains("（必填）"), "prompts must mark required parameters");
    }

    @Test
    void waitingApprovalOpensTheApprovalDrawerAndRequiresAnApprover() {
        String html = FrontendTestSupport.indexHtml();
        String app = FrontendTestSupport.appJs();
        assertTrue(html.contains("agentApprovalDrawer"), "approval drawer missing");
        assertTrue(app.contains("openApproval("), "approval suspension must open the approval UI");
        assertTrue(app.contains("WAITING_APPROVAL"), "approval suspension state missing");
        assertTrue(app.contains("autonomousApprovedBy"), "approver identity input missing");
        assertTrue(app.contains("批准必须提供审批人标识"),
                "approval without an approver identity must be blocked in the UI");
        assertTrue(app.contains("securityFindings"), "the approval UI must show security findings");
        assertTrue(app.contains("smokeReport"), "the approval UI must show the smoke report");
    }

    @Test
    void theTimelineRendersStepsAndToolCalls() {
        String app = FrontendTestSupport.appJs();
        // The timeline uses the redesigned classes; every step renders a role label.
        assertTrue(app.contains("timeline-step"), "timeline step container missing");
        assertTrue(app.contains("tiny-badge"), "step badge style missing");
        assertTrue(app.contains("toolName"), "tool name must be rendered");
        assertTrue(app.contains("AUTONOMOUS_STATUS_LABELS"), "status label mapping missing");
        String css = FrontendTestSupport.stylesCss();
        assertTrue(css.contains(".timeline-step"), "timeline step style missing");
        assertTrue(css.contains(".tiny-badge"), "step badge style missing");
    }

    @Test
    void theFrontendNeverAutoApprovesOrAutoFillsParameters() {
        String app = FrontendTestSupport.appJs();
        assertFalse(app.contains("submitApproval(true)") && !app.contains("autonomousApprovedBy"),
                "approval must always go through the approver dialog");
        assertFalse(app.contains("window.confirm(") && app.contains("submitApproval"),
                "no confirm()-based auto approval bypass");
        assertFalse(app.contains("submitParams(this.sessionId, {}"),
                "params must come from the dialog, never an empty auto-submit");
    }

    @Test
    void theTimelineRendersIncrementallyWithoutReRendering() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("lastRenderedSteps"), "incremental rendering must be tracked");
        assertTrue(app.contains("steps.length <= this.lastRenderedSteps"),
                "already rendered steps must be skipped");
    }

    @Test
    void sessionFinishRestoresTheControlsAndRefreshesTheWorkbench() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("目标已完成") || app.contains("自主会话结束"),
                "final status message missing");
        assertTrue(app.contains("this.wb.refreshJobs()"), "finished sessions must refresh jobs");
        assertTrue(app.contains("this.wb.refreshTemplates()"), "finished sessions must refresh templates");
    }

    @Test
    void noAbsolutePathsInTheAutonomousPanelCode() {
        String app = FrontendTestSupport.appJs();
        assertTrue(!app.contains("C:\\") && !app.contains("D:\\"),
                "the autonomous panel must never expose local absolute paths");
    }
}
