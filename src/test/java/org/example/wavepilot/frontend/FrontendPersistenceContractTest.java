package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The workbench must survive page refreshes while the project keeps running: chat history
 * is restored from sessionStorage, an unfinished goal session is re-adopted from the
 * server-side session store, and the hero heading carries a formal product name.
 */
class FrontendPersistenceContractTest {

    @Test
    void heroHeadingUsesAFormalProductName() {
        String html = FrontendTestSupport.indexHtml();
        assertFalse(html.contains("把实验目标直接告诉 Agent"),
                "the informal hero copy must be gone");
        assertTrue(html.contains("实验目标编排"), "a formal heading is expected");
        assertTrue(html.contains("AGENT WORKSPACE"), "the eyebrow must stay");
    }

    @Test
    void chatHistoryIsPersistedAndRestoredAcrossRefreshes() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("saveChatState"), "chat state must be saved");
        assertTrue(app.contains("restoreChatState"), "chat state must be restored on load");
        assertTrue(app.contains("wavepilot.chatHistory"), "chat history key missing");
        assertTrue(app.contains("sessionStorage.setItem"), "history must use sessionStorage");
        assertTrue(app.contains("messages.slice(-50)"), "history must be bounded");
    }

    @Test
    void unfinishedGoalSessionIsReAdoptedAfterRefresh() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("restoreActiveGoal"), "goal restore entry point missing");
        assertTrue(app.contains("wavepilot.activeGoal"), "active goal key missing");
        assertTrue(app.contains("saveActiveGoal"), "active goal must be saved");
        assertTrue(app.contains("clearActiveGoal"), "finished sessions must clear the goal");
        assertTrue(app.contains("this.isTerminal(session.status)"),
                "only unfinished sessions may be re-adopted");
    }

    @Test
    void theRestoreRunsOnPageLoad() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("window.workbench.restoreChatState()"),
                "chat restore must run on DOMContentLoaded");
        assertTrue(app.contains("window.workbench.autonomousPanel.restoreActiveGoal()"),
                "goal restore must run on DOMContentLoaded");
    }

    @Test
    void agentAnalysisOutputStillStreamsIntoTheConversation() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("Agent 分析结果"), "analysis label must be streamed");
        assertTrue(app.contains("session.analysis"), "analysis must come from the session");
    }
}
