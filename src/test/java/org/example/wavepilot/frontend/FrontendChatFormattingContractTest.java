package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat area must render assistant output as formatted markdown (headings, bold,
 * lists, tables) instead of raw text, output long answers with a streaming typewriter,
 * keep user messages as plain text, and surface the final experiment report inside the
 * conversation instead of only printing the report id.
 */
class FrontendChatFormattingContractTest {

    @Test
    void assistantMessagesAreRenderedAsMarkdownNotPlainText() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("renderMarkdown"), "markdown renderer missing");
        assertTrue(app.contains("marked.parse"), "marked must be used to render chat text");
        assertTrue(app.contains("chat-md"), "markdown wrapper element missing");
        assertTrue(app.contains("div.innerHTML = renderMarkdown(text)"),
                "assistant messages must render markdown into the bubble");
    }

    @Test
    void userMessagesStayPlainTextAndAreNotRendered() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("role === 'assistant'"),
                "chat rendering must branch on the message role");
        assertTrue(app.contains("div.textContent = text"),
                "non-assistant messages must stay plain text");
    }

    @Test
    void htmlIsEscapedBeforeMarkdownRenderingAndLinksAreAllowlisted() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("escapeHtml"), "HTML escaping must protect against injection");
        assertTrue(app.contains("&lt;"), "escaping must cover angle brackets");
        assertTrue(app.contains("sanitizeLinks"), "link sanitizer missing");
        assertTrue(app.contains("http://") && app.contains("https://"),
                "only http/https links may be allowed through");
        assertTrue(app.contains("已阻止不安全链接"), "blocked links must be visibly reported");
    }

    @Test
    void longAnswersStreamWithATypewriterAndIncrementalMarkdown() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("appendChatStreaming"), "streaming entry point missing");
        assertTrue(app.contains("streamIntoChat"), "stream driver missing");
        assertTrue(app.contains("setInterval"), "streaming must be incremental");
        assertTrue(app.contains("renderMarkdownStreaming"),
                "incremental text must re-parse markdown each frame");
        assertTrue(app.contains("stream-pending"),
                "incomplete trailing lines must be rendered as pending text");
    }

    @Test
    void theAgentAnalysisResultIsStreamedIntoTheConversation() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("Agent 分析结果"), "the analysis label must be streamed into chat");
        assertTrue(app.contains("session.analysis"), "the analysis must come from the session");
        assertTrue(app.contains("appendChatStreaming"), "the analysis must use the streaming renderer");
        assertTrue(app.contains("appendAnalysisStep"), "the analysis stays auditable in the timeline");
        assertTrue(app.contains("'目标已完成"), "completion message still present");
    }

    @Test
    void theTemplateReportBodyStaysOutOfChatAndKeepsItsBoundaries() {
        String app = FrontendTestSupport.appJs();
        assertFalse(app.contains("appendReportToChat"),
                "the template report body must not be streamed into the chat");
        assertTrue(app.contains("正文见「结果与证据」页"),
                "the chat only points to the report instead of duplicating its body");
        assertTrue(app.contains("reportId"), "the report id is still disclosed");
        assertTrue(app.contains("生成方式"), "the results page still discloses the generation method");
        assertTrue(app.contains("algorithmValidated=false"),
                "the algorithm-not-validated boundary survives in the results page");
    }

    @Test
    void theResultsPageUsesTheSameSafeRendererWithAFooterElement() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("renderReport"), "report page renderer still exists");
        assertTrue(app.contains("report-footer"), "report footer must be a separate element");
        assertFalse(app.contains("reportContent.textContent +="),
                "the old textContent footer bug must not come back");
    }

    @Test
    void parameterPromptsAreDeduplicatedToAvoidSpam() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("lastParamAsk"), "the dedup signature state must exist");
        assertTrue(app.contains("sessionId + '|' + names"),
                "the dedup key must combine session and parameter set");
        assertTrue(app.contains("上次补充的参数没有被解析出来"),
                "a repeat suspension must explain why instead of re-asking verbatim");
    }

    @Test
    void chatMarkdownStylesExistInTheStylesheet() {
        String css = FrontendTestSupport.stylesCss();
        assertTrue(css.contains(".chat-msg .chat-md"), "markdown wrapper style missing");
        assertTrue(css.contains(".chat-msg .chat-md h1"), "heading style missing");
        assertTrue(css.contains(".chat-msg .chat-md table"), "table style missing");
        assertTrue(css.contains(".chat-msg .chat-md pre"), "code block style missing");
        assertTrue(css.contains(".stream-pending"), "pending-line style missing");
        assertTrue(css.contains(".report-footer"), "report footer style missing");
    }
}
