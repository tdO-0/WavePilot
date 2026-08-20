package org.example.wavepilot.frontend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The workbench template library must expose the full candidate lifecycle safely. */
class FrontendTemplateLibraryContractTest {

    @Test
    void workbenchCallsAllTemplateCatalogEndpoints() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("/wavepilot/templates'"), "template list endpoint missing");
        assertTrue(app.contains("/wavepilot/template-candidates'"), "candidate list endpoint missing");
        assertTrue(app.contains("/template-candidates/generate"), "generate endpoint missing");
        assertTrue(app.contains("/validate'"), "validate endpoint missing");
        assertTrue(app.contains("/smoke'"), "smoke endpoint missing");
        assertTrue(app.contains("/approve'"), "approve endpoint missing");
        assertTrue(app.contains("/reject'"), "reject endpoint missing");
        assertTrue(app.contains("/deactivate") || app.contains("rollback"), "lifecycle endpoints missing");
    }

    @Test
    void approvalRequiresAnExplicitApproverInTheFrontend() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("approvedBy"), "approval must carry an approver identity");
        assertTrue(app.contains("请输入审批人标识"), "the frontend must ask for an explicit approver");
        assertTrue(app.contains("批准发布是用户操作"), "the agent boundary must be stated in the UI");
    }

    @Test
    void statusLabelsAreLocalizedAndBoundariesNeverDisguised() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("SMOKE_PENDING"), "candidate status mapping missing");
        assertTrue(app.contains("待人工审批"), "review status must be localized");
        assertTrue(app.contains("MATLAB Smoke 未执行"), "unexecuted smoke must be explicit");
        assertTrue(app.contains("algorithmValidated=false"), "unvalidated algorithm flag must show");
        assertTrue(app.contains("模板能运行不代表算法经过科学验证"),
                "the runnable-vs-validated disclaimer must show");
        assertFalse(anyLineCombines(app, "algorithmValidated", "已验证）") && !app.contains("yesNo"),
                "no accidental claim of validation");
    }

    @Test
    void candidateFilesAreNeverShownToTheWorkbench() {
        String app = FrontendTestSupport.appJs();
        assertFalse(app.contains("candidate.files"), "candidate file content must not be rendered");
    }

    @Test
    void templateListIsDeduplicatedByTemplateId() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("byId.get(record.templateId)"),
                "the template list must deduplicate by templateId");
        assertTrue(app.contains("激活版本"), "each template row must show its active version");
        assertFalse(app.contains("item.addEventListener('click', () => this.rollbackToVersion"),
                "clicking a version row must never auto-activate it");
    }

    @Test
    void aTemplateCanFillTheExperimentSpecEditor() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("用此模板创建实验"), "template-to-spec button missing");
        assertTrue(app.contains("customParameters"), "spec fill must set customParameters");
        assertTrue(app.contains("experimentTypeId"), "spec fill must set experimentTypeId");
        assertTrue(app.contains("useTemplateForExperiment"), "fill handler missing");
    }

    @Test
    void specFillNeverWritesNullParameters() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("openParamFillDialog"), "template fill must open the parameter dialog");
        assertTrue(app.contains("建议值"), "suggested values must be shown");
        assertFalse(app.contains(": null;"),
                "no null parameter values may be written into customParameters");
    }

    @Test
    void theParameterFillDialogExistsAndConfirmsIntoSpec() {
        String html = FrontendTestSupport.indexHtml();
        assertTrue(html.contains("paramFillDialog"), "parameter dialog missing in the layout");
        assertTrue(html.contains("paramFillConfirmBtn"), "confirm button missing");
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("confirmParamFill"), "confirm handler missing");
        assertTrue(app.contains("this.validateSpec()"),
                "confirming must run the Java validation");
        assertTrue(app.contains("data-param-name"), "parameter rows must be queryable");
    }

    @Test
    void builtInTemplatesAlsoFillTheSpecEditor() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("内置模板（如极化码）没有声明式定义"),
                "built-in templates must be handled without a declarative definition");
        assertTrue(app.contains("已填充内置模板的极化码 Spec 骨架"),
                "built-in fill must show a clear message");
        assertFalse(app.contains("该模板没有声明式定义，无法自动填充"),
                "the confusing dead-end message must be gone");
    }

    @Test
    void sseIsOpenedExactlyOncePerJob() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("不再重复打开"), "duplicate SSE opening must be prevented");
        assertTrue(app.contains("selectJob(job.jobId)"), "single connection must go through selectJob");
        assertFalse(app.contains("this.openSse(job.jobId);"),
                "createJob must not open SSE a second time");
    }

    @Test
    void sseNotificationsAreRateLimited() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("lastSseErrorAt"), "SSE error toasts must be rate-limited");
        assertTrue(app.contains("sseNotified"), "SSE connect toasts must be one-shot");
    }

    @Test
    void templateDetailDeclaresVersionsBeforeRendering() {
        String app = FrontendTestSupport.appJs();
        int versionsDecl = app.indexOf("const versions = detail.versions");
        int versionsUse = app.indexOf("for (const v of versions)");
        assertTrue(versionsDecl >= 0, "detail.versions must be declared");
        assertTrue(versionsUse > versionsDecl,
                "versions must be declared before it is iterated (no ReferenceError)");
    }

    @Test
    void versionHistoryOffersExplicitActivateButtons() {
        String app = FrontendTestSupport.appJs();
        assertTrue(app.contains("设为激活"), "non-active versions must carry an explicit activate button");
        assertTrue(app.contains("rollbackToVersion"), "explicit activation must go through rollback");
        assertTrue(app.contains("当前激活"), "the active version must be labelled");
    }

    @Test
    void noAbsolutePathsInTheTemplateLibraryCode() {
        String app = FrontendTestSupport.appJs();
        assertTrue(!app.contains("C:\\") && !app.contains("D:\\"),
                "the template library must never expose local absolute paths");
    }

    @Test
    void theTemplateLibrarySectionExistsInTheLayout() {
        String html = FrontendTestSupport.indexHtml();
        assertTrue(html.contains("模板库"), "template library section missing");
        assertTrue(html.contains("generateCandidateBtn"), "generate button missing");
        assertTrue(html.contains("approveCandidateBtn"), "approve button missing");
        assertTrue(html.contains("templateList"), "template list container missing");
        assertTrue(html.contains("candidateList"), "candidate list container missing");
    }

    private boolean anyLineCombines(String source, String first, String second) {
        return java.util.Arrays.stream(source.split("\n"))
                .anyMatch(line -> line.contains(first) && line.contains(second));
    }
}
