/* ============================================================
 * WavePilot 通信仿真实验智能平台 - 工作台前端
 * 纯静态 HTML/JS/CSS；所有校验、算法、报告 Grounding 都在服务端。
 * 前端只负责展示与操作，绝不把 RUNNING 显示为 SUCCEEDED，
 * 绝不把 similarityScore/CitationStatus/Replay 一致性显示为概率。
 * 展示原则：中文为主、技术标识（英文枚举）为辅，方便阅读也保留可追溯性。
 * ============================================================ */

/* 状态中英对照：界面一律显示"中文（英文标识）" */
const STATUS_LABELS = {
    CREATED: '已创建', VALIDATED: '已通过校验', QUEUED: '排队中', RUNNING: '运行中',
    VALIDATING_RESULT: '校验结果中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消'
};
function statusLabel(status) {
    return STATUS_LABELS[status] ? STATUS_LABELS[status] + '（' + status + '）' : status;
}
function statusShort(status) {
    return STATUS_LABELS[status] ? STATUS_LABELS[status] : status;
}

/* ---------------- Markdown 渲染（聊天区） ----------------
 * 聊天区的 assistant 消息以 markdown 渲染（标题/列表/代码/表格/引用），
 * user 消息保持纯文本。渲染前先转义 HTML，防模型输出中的原始 HTML 注入；
 * 链接只允许 http/https/mailto/# 与站内相对路径。 */
function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function sanitizeLinks(html) {
    return html.replace(/<a href="([^"]*)"[^>]*>/g, (match, href) => {
        const h = href.trim().toLowerCase();
        if (h.startsWith('http://') || h.startsWith('https://') || h.startsWith('mailto:')
                || h.startsWith('#') || h.startsWith('/')) {
            return match;
        }
        return '<span class="error-text">（已阻止不安全链接）</span>';
    });
}

/** 完整文本 → markdown HTML；marked 不可用时退化为转义纯文本。 */
function renderMarkdown(text) {
    if (typeof marked === 'undefined' || typeof marked.parse !== 'function') {
        return '<div class="chat-md">' + escapeHtml(text).replace(/\n/g, '<br>') + '</div>';
    }
    const html = marked.parse(escapeHtml(text), { breaks: true, gfm: true });
    return '<div class="chat-md">' + sanitizeLinks(html) + '</div>';
}

/** 流式增量文本：最后一行可能未写完（标题/粗体/列表未闭合），
 * 已完整的行正常渲染 markdown，未完成行挂起为半透明纯文本，避免闪烁。 */
function renderMarkdownStreaming(partial) {
    if (typeof marked === 'undefined' || typeof marked.parse !== 'function') {
        return '<div class="chat-md">' + escapeHtml(partial).replace(/\n/g, '<br>') + '</div>';
    }
    const text = String(partial);
    const lastBreak = text.lastIndexOf('\n');
    const complete = lastBreak >= 0 ? text.slice(0, lastBreak) : '';
    const pending = lastBreak >= 0 ? text.slice(lastBreak + 1) : text;
    const html = complete ? marked.parse(escapeHtml(complete), { breaks: true, gfm: true }) : '';
    const pendingHtml = pending ? '<span class="stream-pending">' + escapeHtml(pending) + '</span>' : '';
    return '<div class="chat-md">' + sanitizeLinks(html) + pendingHtml + '</div>';
}

/* 阶段名中英对照 */
const STAGE_LABELS = {
    CREATED: '已创建', VALIDATE_SPEC: '校验配置', RUN_EXPERIMENT: '执行实验',
    VALIDATE_RESULT: '校验结果', REGISTER_ARTIFACTS: '登记产物', VALIDATING_RESULT: '校验结果中',
    QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消'
};
function stageLabel(stage) {
    return STAGE_LABELS[stage] ? STAGE_LABELS[stage] + '（' + stage + '）' : stage;
}

/* Artifact 类型中英对照 */
const ARTIFACT_TYPE_LABELS = {
    EXPERIMENT_SPEC: '实验配置', EXPERIMENT_PLAN: '实验计划', ACCURACY_CSV: '准确率数据(CSV)',
    ACCURACY_CURVE: '准确率曲线(PNG)', MAT_RESULT: 'MAT 结果', RUN_LOG: '运行日志',
    SUMMARY_JSON: '结果摘要', FINAL_REPORT: '最终报告', REPLAY_MANIFEST: 'Replay 清单',
    REPLAY_COMPARISON: 'Replay 对比', EVAL_REPORT: 'Eval 报告', EVAL_CASE_RESULTS: 'Eval 逐例结果',
    EVAL_COMPARISON: 'Eval 对比'
};
function artifactTypeLabel(type) {
    return ARTIFACT_TYPE_LABELS[type] ? ARTIFACT_TYPE_LABELS[type] + '（' + type + '）' : type;
}

/* 指标名中英对照 */
const METRIC_LABELS = {
    specParseAccuracy: 'Spec 字段解析正确率', missingParameterDetectionRate: '缺参识别率',
    invalidParameterBlockRate: '非法参数拦截率', toolSelectionAccuracy: '工具选择正确率',
    forbiddenToolBlockRate: '禁止工具调用拦截率', jobSubmissionSuccessRate: 'Job 提交成功率',
    knowledgeRetrievalRate: '知识检索命中率', artifactCitationConsistencyRate: 'Artifact 引用一致率',
    reportGroundingRate: '报告数值 Grounding 率', replayConsistencyRate: 'Replay 一致率',
    overallTaskCompletionRate: '总任务完成率'
};
function metricLabel(name) {
    return METRIC_LABELS[name] ? METRIC_LABELS[name] + '（' + name + '）' : name;
}

/* 布尔值中文化 */
function yesNo(value) { return value ? '是' : '否'; }

/* 模板与候选状态中英对照 */
const TEMPLATE_STATUS_LABELS = {
    ACTIVE: '已激活', INACTIVE: '未激活', ARCHIVED: '已归档'
};
const CANDIDATE_STATUS_LABELS = {
    DRAFT: '草稿', GENERATED: '已生成', VALIDATING: '校验中', VALIDATION_FAILED: '校验失败',
    SMOKE_PENDING: '待 Smoke', SMOKE_PASSED: 'Smoke 通过', SMOKE_FAILED: 'Smoke 失败',
    REVIEW_REQUIRED: '待人工审批', APPROVED: '已批准', ACTIVE: '已激活',
    REJECTED: '已拒绝', ARCHIVED: '已归档', ROLLED_BACK: '已回滚',
    REQUIRES_CUSTOM_EXTENSION: '需要自定义扩展'
};
function templateStatusLabel(status) {
    return TEMPLATE_STATUS_LABELS[status] ? TEMPLATE_STATUS_LABELS[status] + '（' + status + '）' : status;
}
function candidateStatusLabel(status) {
    return CANDIDATE_STATUS_LABELS[status] ? CANDIDATE_STATUS_LABELS[status] + '（' + status + '）' : status;
}

/* 自主会话状态中英对照 */
const AUTONOMOUS_STATUS_LABELS = {
    ANALYZING: '解析意图', WAITING_PARAMS: '等待填写参数', CHECKING_TEMPLATE: '检查模板',
    GENERATING_CANDIDATE: '生成候选模板', VALIDATING: '校验候选', SMOKING: 'Smoke 测试',
    WAITING_APPROVAL: '等待审批发布', RUNNING_EXPERIMENT: '实验运行中',
    GENERATING_REPORT: '生成报告', SUCCEEDED: '成功', FAILED: '失败',
    CANCELLED: '已取消', BLOCKED: '已阻断'
};
function autonomousStatusLabel(status) {
    return AUTONOMOUS_STATUS_LABELS[status]
        ? AUTONOMOUS_STATUS_LABELS[status] + '（' + status + '）' : status;
}
function autonomousStatusClass(status) {
    if (status === 'WAITING_PARAMS' || status === 'WAITING_APPROVAL') return 'autonomous-status-waiting';
    if (status === 'SUCCEEDED') return 'autonomous-status-done';
    if (status === 'FAILED' || status === 'CANCELLED' || status === 'BLOCKED') return 'autonomous-status-fail';
    return 'autonomous-status-active';
}

class WavePilotWorkbench {
    constructor() {
        this.apiBaseUrl = '/api';
        // 同一会话的 conversationId 保存在 sessionStorage：刷新页面后仍继续同一对话。
        this.conversationId = null;
        try { this.conversationId = sessionStorage.getItem('wavepilot.conversationId') || null; }
        catch (ignored) { }
        this.selectedJobId = null;
        this.jobs = [];
        this.artifacts = [];
        this.replays = [];
        this.sse = null;
        this.lastSseErrorAt = 0;
        this.sseNotified = false;
        this.lastEvalIds = [];
        this.paramFillMode = 'template';
        this.initElements();
        this.bindEvents();
        this.autonomousPanel = new AutonomousPanel(this);
        this.refreshJobs();
        this.refreshReplays();
    }

    initElements() {
        this.$ = (id) => document.getElementById(id);
        this.specJson = this.$('specJson');
        this.chatMessages = this.$('chatMessages');
        this.specValidationResult = this.$('specValidationResult');
        this.planPreview = this.$('planPreview');
        this.jobList = this.$('jobList');
        this.jobStatusLine = this.$('jobStatusLine');
        this.progressBar = this.$('progressBar');
        this.jobStage = this.$('jobStage');
        this.currentPointLine = this.$('currentPointLine');
        this.jobBoundaryBadge = this.$('jobBoundaryBadge');
        this.jobError = this.$('jobError');
        this.artifactList = this.$('artifactList');
        this.artifactMetadata = this.$('artifactMetadata');
        this.pngPreview = this.$('pngPreview');
        this.reportContent = this.$('reportContent');
        this.resultReportState = this.$('resultReportState');
        this.citationList = this.$('citationList');
        this.reportError = this.$('reportError');
        this.replayList = this.$('replayList');
        this.replayComparison = this.$('replayComparison');
        this.replayError = this.$('replayError');
        this.evalMetrics = this.$('evalMetrics');
        this.evalFailedCases = this.$('evalFailedCases');
        this.evalComparison = this.$('evalComparison');
        this.evalError = this.$('evalError');
        this.globalBoundaryBadge = this.$('globalBoundaryBadge');
        this.templateList = this.$('templateList');
        this.candidateList = this.$('candidateList');
        this.templateDetail = this.$('templateDetail');
        this.templateLibraryError = this.$('templateLibraryError');
        this.selectedCandidateId = null;
        this.selectedTemplateId = null;
    }

    bindEvents() {
        this.$('chatSendBtn').addEventListener('click', () => this.sendChat());
        this.$('knowledgeUploadBtn').addEventListener('click', () => this.uploadKnowledge());
        this.$('validateSpecBtn').addEventListener('click', () => this.validateSpec());
        this.$('createJobBtn').addEventListener('click', () => this.createJob());
        this.$('cancelJobBtn').addEventListener('click', () => this.cancelJob());
        this.$('refreshJobsBtn').addEventListener('click', () => this.refreshJobs());
        this.$('generateReportBtn').addEventListener('click', () => this.generateReport());
        this.$('validateReportBtn').addEventListener('click', () => this.validateReport());
        this.$('replayBtn').addEventListener('click', () => this.startReplay());
        this.$('refreshReplaysBtn').addEventListener('click', () => this.refreshReplays());
        this.$('evalRunBtn').addEventListener('click', () => this.runEval('stub-v1'));
        this.$('evalRunRegressedBtn').addEventListener('click', () => this.runEval('stub-v2'));
        this.$('evalCompareBtn').addEventListener('click', () => this.compareEvals());
        this.$('generateCandidateBtn').addEventListener('click', () => this.generateCandidate());
        this.$('refreshTemplatesBtn').addEventListener('click', () => this.refreshTemplates());
        this.$('refreshCandidatesBtn').addEventListener('click', () => this.refreshCandidates());
        this.$('validateCandidateBtn').addEventListener('click', () => this.validateSelectedCandidate());
        this.$('smokeCandidateBtn').addEventListener('click', () => this.smokeSelectedCandidate());
        this.$('approveCandidateBtn').addEventListener('click', () => this.approveSelectedCandidate());
        this.$('rejectCandidateBtn').addEventListener('click', () => this.rejectSelectedCandidate());
        this.$('rollbackTemplateBtn').addEventListener('click', () => this.rollbackSelectedTemplate());
        this.$('deactivateTemplateBtn').addEventListener('click', () => this.deactivateSelectedTemplate());
        this.$('paramFillConfirmBtn').addEventListener('click', () => this.confirmParamFill());
        this.$('paramFillCancelBtn').addEventListener('click', () => this.$('paramFillDialog').close());
        this.refreshTemplates();
        this.refreshCandidates();
    }

    /* ---------------- 模板库 ---------------- */
    async refreshTemplates() {
        try {
            const templates = await this.api('/wavepilot/templates');
            this.renderTemplateList(templates);
        } catch (error) {
            this.templateLibraryError.textContent = '模板列表加载失败：' + error.message;
        }
    }

    renderTemplateList(templates) {
        this.templateList.innerHTML = '';
        if (!templates.length) {
            this.templateList.textContent = '（暂无正式模板）';
            return;
        }
        // 列表按 templateId 去重（每个模板一行，显示激活版本），避免误把版本当独立选择项。
        const byId = new Map();
        for (const record of templates) {
            const existing = byId.get(record.templateId);
            if (!existing || (record.status === 'ACTIVE' && existing.status !== 'ACTIVE')) {
                byId.set(record.templateId, record);
            }
        }
        for (const record of byId.values()) {
            const item = document.createElement('div');
            item.className = 'job-item' + (record.templateId === this.selectedTemplateId ? ' selected' : '');
            item.innerHTML = '<strong>' + record.displayName + '</strong> '
                + record.templateId + ' | 激活版本 ' + record.activeVersion + '<br>'
                + templateStatusLabel(record.status)
                + ' | 来源：' + record.source
                + ' | 可运行：' + yesNo(record.operationalValidated)
                + ' | 算法已验证：' + yesNo(record.algorithmValidated)
                + ' | ' + record.classification;
            item.addEventListener('click', () => this.showTemplateDetail(record.templateId));
            this.templateList.appendChild(item);
        }
    }

    async showTemplateDetail(templateId) {
        this.selectedTemplateId = templateId;
        this.renderTemplateList(await this.api('/wavepilot/templates'));
        try {
            const detail = await this.api('/wavepilot/templates/' + templateId);
            const active = detail.active || {};
            let text = '【' + active.displayName + '（' + templateId + '）】'
                + ' 激活版本：' + active.activeVersion
                + ' | 可运行：' + yesNo(active.operationalValidated)
                + ' | 算法已验证：' + yesNo(active.algorithmValidated)
                + ' | 分类：' + active.classification + '\n';
            text += '⚠️ 算法验证边界：' + (active.algorithmValidated ? '声称已验证'
                : 'algorithmValidated=false（模板能运行不代表算法经过科学验证）') + '\n';
            if (detail.definition) {
                text += '参数：' + (detail.definition.parameters || []).map(p =>
                    p.name + (p.required ? '（必填）' : '') + (p.unit ? '[' + p.unit + ']' : '')).join(', ') + '\n';
                text += '输出列：' + ((detail.definition.outputs || {}).requiredColumns || []).join(', ') + '\n';
                text += '指标：' + (detail.definition.metrics || []).map(m =>
                    m.displayName + '（' + m.sourceColumn + '/' + m.aggregation + '）').join(', ') + '\n';
                text += 'Replay 比较列：' + (detail.definition.replay || []).map(r =>
                    r.comparisonColumn + '（容差 ' + r.maxAbsoluteTolerance + '）').join(', ') + '\n';
            }
            // 一键把模板参数填入中间栏 Spec 编辑器，打通"模板 -> 实验"闭环。
            text += '\n\n版本历史：';
            this.templateDetail.textContent = text;
            const useButton = document.createElement('button');
            useButton.textContent = '用此模板创建实验（填充 Spec）';
            useButton.addEventListener('click', () => this.useTemplateForExperiment(templateId));
            this.templateDetail.appendChild(useButton);
            const versions = detail.versions || [];
            const versionBox = document.createElement('div');
            for (const v of versions) {
                const row = document.createElement('div');
                row.className = 'job-item';
                row.style.marginBottom = '3px';
                row.innerHTML = '版本 ' + v.version + '（' + templateStatusLabel(v.status) + '）'
                    + (v.status === 'ACTIVE' ? ' ← 当前激活' : '')
                    + (v.status !== 'ACTIVE'
                        ? ' <button class="secondary" data-activate-version="' + v.version + '">设为激活</button>'
                        : '');
                if (v.status !== 'ACTIVE') {
                    row.querySelector('button').addEventListener('click', () =>
                        this.rollbackToVersion(this.selectedTemplateId, v.version));
                }
                versionBox.appendChild(row);
            }
            this.templateDetail.appendChild(versionBox);
        } catch (error) {
            this.templateDetail.textContent = '模板详情加载失败：' + error.message;
        }
    }

    async refreshCandidates() {
        try {
            const candidates = await this.api('/wavepilot/template-candidates');
            this.renderCandidateList(candidates);
        } catch (error) {
            this.templateLibraryError.textContent = '候选列表加载失败：' + error.message;
        }
    }

    renderCandidateList(candidates) {
        this.candidateList.innerHTML = '';
        if (!candidates.length) {
            this.candidateList.textContent = '（暂无候选模板）';
            return;
        }
        for (const candidate of candidates) {
            const item = document.createElement('div');
            item.className = 'job-item' + (candidate.candidateId === this.selectedCandidateId ? ' selected' : '');
            const smokeNote = candidate.realSmokeExecuted ? ' | Smoke 已执行'
                : ' | MATLAB Smoke 未执行';
            item.innerHTML = '<strong>' + candidateStatusLabel(candidate.status) + '</strong> '
                + candidate.candidateId + '（' + candidate.templateId + '）'
                + (candidate.failureReason ? ' | ' + candidate.failureReason : '') + smokeNote;
            item.addEventListener('click', () => {
                this.selectedCandidateId = candidate.candidateId;
                this.renderCandidateList(candidates);
                this.showCandidateDetail(candidate.candidateId);
            });
            this.candidateList.appendChild(item);
        }
    }

    async showCandidateDetail(candidateId) {
        try {
            const candidate = await this.api('/wavepilot/template-candidates/' + candidateId);
            let text = '候选 ' + candidate.candidateId + '：' + candidateStatusLabel(candidate.status)
                + ' | 来源：' + candidate.source + '\n';
            text += '⚠️ 边界：算法验证 = ' + (candidate.definitionYaml
                ? '由定义声明（algorithmValidated=false）' : '未知')
                + ' | Smoke：' + (candidate.realSmokeExecuted ? '已执行' : 'MATLAB Smoke 未执行') + '\n';
            text += '⚠️ 批准发布是用户操作，Agent 无权自行发布。\n';
            const blocked = (candidate.securityFindings || []).filter(f => f.severity === 'BLOCKED');
            const warnings = (candidate.securityFindings || []).filter(f => f.severity === 'WARNING');
            text += '安全检查：BLOCKED ' + blocked.length + ' 项' + (blocked.length
                ? '（' + blocked.map(f => f.ruleId + '@' + f.file + ':' + f.line).join('，') + '）' : '')
                + '，WARNING ' + warnings.length + ' 项\n';
            text += 'Smoke 报告：' + (candidate.smokeReport || '（未执行）') + '\n';
            text += '假设：' + (candidate.assumptions || []).join('；') + '\n';
            text += '待确认问题：' + (candidate.unresolvedQuestions || []).join('；') + '\n';
            text += '失败原因：' + (candidate.failureReason || '无');
            this.templateDetail.textContent = text;
        } catch (error) {
            this.templateDetail.textContent = '候选详情加载失败：' + error.message;
        }
    }

    async generateCandidate() {
        const request = this.$('templateRequestInput').value.trim();
        if (!request) { this.toast('请先描述要生成的新模板', true); return; }
        this.templateLibraryError.textContent = '';
        try {
            const candidate = await this.api('/wavepilot/template-candidates/generate', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ request: request })
            });
            this.selectedCandidateId = candidate.candidateId;
            this.toast('候选已生成：' + candidate.candidateId + '（' + candidateStatusLabel(candidate.status) + '）');
            await this.validateCandidate(candidate.candidateId);
            await this.smokeCandidate(candidate.candidateId);
            this.refreshCandidates();
        } catch (error) {
            this.templateLibraryError.textContent = '候选生成失败：' + error.message;
        }
    }

    async validateCandidate(candidateId) {
        const candidate = await this.api('/wavepilot/template-candidates/' + candidateId + '/validate',
            { method: 'POST' });
        this.showCandidateDetail(candidate.candidateId);
        this.refreshCandidates();
        return candidate;
    }

    async validateSelectedCandidate() {
        if (!this.selectedCandidateId) { this.toast('请先选择候选模板', true); return; }
        await this.validateCandidate(this.selectedCandidateId);
    }

    async smokeCandidate(candidateId) {
        const candidate = await this.api('/wavepilot/template-candidates/' + candidateId + '/smoke',
            { method: 'POST' });
        this.showCandidateDetail(candidate.candidateId);
        this.refreshCandidates();
        return candidate;
    }

    async smokeSelectedCandidate() {
        if (!this.selectedCandidateId) { this.toast('请先选择候选模板', true); return; }
        await this.smokeCandidate(this.selectedCandidateId);
    }

    async approveSelectedCandidate() {
        if (!this.selectedCandidateId) { this.toast('请先选择候选模板', true); return; }
        // 批准是显式用户动作：必须填写审批人，绝不自动执行。
        const approvedBy = window.prompt('请输入审批人标识（批准后模板将原子发布为 ACTIVE）：', 'user');
        if (!approvedBy || !approvedBy.trim()) { this.toast('审批已取消：缺少审批人', true); return; }
        try {
            const candidate = await this.api('/wavepilot/template-candidates/'
                + this.selectedCandidateId + '/approve', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approvedBy: approvedBy.trim() })
            });
            this.toast('已批准并发布：' + candidate.templateId + ' ' + candidate.version);
            this.refreshCandidates();
            this.refreshTemplates();
        } catch (error) {
            this.templateLibraryError.textContent = '批准发布失败：' + error.message;
        }
    }

    async useTemplateForExperiment(templateId) {
        try {
            const detail = await this.api('/wavepilot/templates/' + templateId);
            const definition = detail.definition;
            if (!definition) {
                // 内置模板（如极化码）没有声明式定义，走 Java 专用参数：直接填充极化码 Spec 骨架。
                this.specJson.value = JSON.stringify({
                    experimentType: 'POLAR_CODE_K_IDENTIFICATION',
                    codeLengths: [32, 64],
                    errorRateStart: 0.0, errorRateEnd: 0.02, errorRateStep: 0.01,
                    sampleCount: 20, monteCarloTimes: 10, randomSeed: 20,
                    outputTypes: ['ACCURACY_CSV', 'RUN_LOG'],
                    description: '使用内置模板 ' + templateId + ' 创建实验'
                }, null, 2);
                this.toast('已填充内置模板的极化码 Spec 骨架；可修改码长/误码率后校验');
                return;
            }
            this.openParamFillDialog(templateId, definition);
        } catch (error) {
            this.templateLibraryError.textContent = '按模板填充 Spec 失败：' + error.message;
        }
    }

    /** 模板参数对话框：列出必填/可选参数，用户确认后生成 Spec 并校验。 */
    openParamFillDialog(templateId, definition) {
        this.paramFillMode = 'template';
        this.paramFillTemplateId = templateId;
        this.paramFillDefinition = definition;
        this.$('paramFillTitle').textContent = '填写模板参数：' + definition.displayName
            + '（' + templateId + '）';
        this.$('paramFillConfirmBtn').textContent = '确认并生成 Spec';
        this.renderParamFillForm(definition.parameters || [], definition);
        this.$('paramFillError').textContent = '';
        this.$('paramFillDialog').showModal();
    }

    /** 自主模式缺参弹窗：只收集参数值，确认后交回自主会话，由模型继续推进。 */
    openAutonomousParamDialog(session) {
        this.paramFillMode = 'autonomous';
        this.paramFillSessionId = session.sessionId;
        const pending = session.pendingParams || {};
        const parameters = pending.parameters || [];
        this.$('paramFillTitle').textContent = '自主模式需要填写参数'
            + (session.request ? '（' + session.request + '）' : '');
        this.$('paramFillConfirmBtn').textContent = '确认并继续';
        this.renderParamFillForm(parameters, null);
        this.$('paramFillError').textContent = '';
        this.$('paramFillDialog').showModal();
    }

    /** 渲染参数表单；definition 仅模板路径用于展示建议值（min/max/enumValues 等）。 */
    renderParamFillForm(parameters, definition) {
        const form = this.$('paramFillForm');
        form.innerHTML = '';
        for (const p of parameters) {
            const row = document.createElement('div');
            row.className = 'param-row';
            const label = document.createElement('label');
            label.textContent = p.name + (p.required ? ' *' : '') + (p.unit ? ' [' + p.unit + ']' : '');
            let input = document.createElement('input');
            input.dataset.paramName = p.name;
            if (p.type === 'INTEGER') { input.type = 'number'; input.step = '1'; }
            else if (p.type === 'NUMBER') { input.type = 'number'; input.step = 'any'; }
            else if (p.type === 'BOOLEAN') {
                input = document.createElement('select');
                input.dataset.paramName = p.name;
                ['true', 'false'].forEach(v => {
                    const option = document.createElement('option');
                    option.value = v; option.textContent = v; input.appendChild(option);
                });
            } else if (p.type === 'ENUM') {
                input = document.createElement('select');
                input.dataset.paramName = p.name;
                (p.enumValues || []).forEach(v => {
                    const option = document.createElement('option');
                    option.value = v; option.textContent = v; input.appendChild(option);
                });
            } else {
                input.type = 'text';
            }
            const suggestion = (p.defaultValue !== undefined && p.defaultValue !== null)
                ? p.defaultValue : (p.min !== undefined && p.min !== null ? p.min : 1);
            input.value = suggestion;
            const hint = document.createElement('div');
            hint.className = 'param-hint';
            hint.textContent = (p.description || '') + ' | 建议值 ' + suggestion
                + (p.min !== undefined ? ' | 范围 [' + p.min : '')
                + (p.max !== undefined ? ', ' + p.max : '') + ']';
            row.appendChild(label);
            row.appendChild(input);
            row.appendChild(hint);
            form.appendChild(row);
        }
    }

    confirmParamFill() {
        const values = this.buildParamValuesFromDialog();
        this.$('paramFillDialog').close();
        if (this.paramFillMode === 'autonomous') {
            this.autonomousPanel.submitParams(this.paramFillSessionId, values);
            return;
        }
        const spec = this.buildSpecFromDialog();
        this.specJson.value = JSON.stringify(spec, null, 2);
        this.toast('已按模板生成 Spec（含你填写的参数），正在校验…');
        this.validateSpec();
    }

    /** 收集对话框中的参数值：数字输入转 Number，其余保留原值；空值跳过。 */
    buildParamValuesFromDialog() {
        const values = {};
        for (const row of this.$('paramFillForm').querySelectorAll('[data-param-name]')) {
            const value = row.value;
            if (value === '' || value === null || value === undefined) continue;
            values[row.dataset.paramName] = row.type === 'number' ? Number(value) : value;
        }
        return values;
    }

    buildSpecFromDialog() {
        const customParameters = this.buildParamValuesFromDialog();
        const definition = this.paramFillDefinition;
        const spec = {
            experimentType: 'POLAR_CODE_K_IDENTIFICATION',
            experimentTypeId: definition.experimentTypeId,
            codeLengths: [32],
            errorRateStart: 0.0, errorRateEnd: 0.1, errorRateStep: 0.05,
            sampleCount: 20, monteCarloTimes: 10, randomSeed: 20,
            outputTypes: ['ACCURACY_CSV', 'RUN_LOG'],
            description: '使用模板 ' + this.paramFillTemplateId + ' 创建实验',
            customParameters: customParameters
        };
        return spec;
    }

    async rollbackSelectedTemplate() {
        if (!this.selectedTemplateId) { this.toast('请先选择一个正式模板', true); return; }
        const version = window.prompt('请输入要回滚到的版本号（回滚只切换激活版本，不删除历史）：', '');
        if (!version || !version.trim()) { this.toast('回滚已取消', true); return; }
        await this.rollbackToVersion(this.selectedTemplateId, version.trim());
    }

    async rollbackToVersion(templateId, version) {
        try {
            await this.api('/wavepilot/templates/' + templateId + '/rollback', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ version: version })
            });
            this.toast('已将 ' + templateId + ' 的激活版本切换为 ' + version);
            this.refreshTemplates();
            this.showTemplateDetail(templateId);
        } catch (error) {
            this.templateLibraryError.textContent = '切换激活版本失败：' + error.message;
        }
    }

    async deactivateSelectedTemplate() {
        if (!this.selectedTemplateId) { this.toast('请先选择一个正式模板', true); return; }
        try {
            await this.api('/wavepilot/templates/' + this.selectedTemplateId + '/deactivate',
                { method: 'POST' });
            this.toast('模板已停用：' + this.selectedTemplateId);
            this.refreshTemplates();
        } catch (error) {
            this.templateLibraryError.textContent = '停用失败：' + error.message;
        }
    }

    async rejectSelectedCandidate() {
        if (!this.selectedCandidateId) { this.toast('请先选择候选模板', true); return; }
        const reason = window.prompt('请输入拒绝原因：', '人工否决');
        try {
            await this.api('/wavepilot/template-candidates/' + this.selectedCandidateId + '/reject', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ reason: reason || '人工否决' })
            });
            this.toast('候选已拒绝');
            this.refreshCandidates();
        } catch (error) {
            this.templateLibraryError.textContent = '拒绝失败：' + error.message;
        }
    }

    /* ---------------- 通用 ----------------
     * 请求失败、任务不存在、Artifact 失效、Replay 失败、
     * Eval Case 失败、报告未生成都以明确错误提示展示。 */
    async api(path, options) {
        const response = await fetch(this.apiBaseUrl + path, options);
        if (!response.ok) {
            let message = 'HTTP ' + response.status;
            try {
                const body = await response.json();
                if (body && body.message) message = body.message;
                if (body && body.errors) message += ' | ' + body.errors.join('; ');
            } catch (ignored) { /* non-JSON error body */ }
            throw new Error(message);
        }
        return response.json();
    }

    toast(message, isError) {
        const area = this.$('toastArea');
        const el = document.createElement('div');
        el.className = 'toast' + (isError ? ' error' : '');
        el.textContent = message;
        area.appendChild(el);
        setTimeout(() => el.remove(), 6000);
    }

    /* ---------------- 边界标识 ----------------
     * 三种轴分开展示：runnerType 是否真实执行（mock 字段）、
     * classification、algorithmValidated。绝不合并为"算法已验证"。 */
    renderBoundary(jobBoundary, mock, algorithmValidated, classification, runnerType) {
        if (!jobBoundary) return;
        if (mock === true) {
            jobBoundary.textContent = 'MOCK EXPERIMENT（模拟实验，未运行 MATLAB）'
                + ' | 简化基线（SIMPLIFIED_BASELINE） | 算法未验证（algorithmValidated=false）';
            jobBoundary.className = 'boundary-badge boundary-mock';
        } else if (mock === false) {
            jobBoundary.textContent = 'REAL MATLAB EXPERIMENT（真实 MATLAB 实验）'
                + ' | 运行器：' + (runnerType || 'unknown')
                + ' | 简化基线（SIMPLIFIED_BASELINE） | 算法未验证（algorithmValidated=false）';
            jobBoundary.className = 'boundary-badge boundary-real';
        } else {
            jobBoundary.textContent = '任务边界：未确定（等待产物）';
            jobBoundary.className = 'boundary-badge boundary-unknown';
        }
        if (classification && classification !== 'SIMPLIFIED_BASELINE') {
            jobBoundary.textContent += ' | 算法类别：' + classification;
        }
    }

    /* ---------------- 对话区：统一的 Agent 输入框 ---------------- */
    async sendChat() {
        const input = this.$('chatInput');
        const text = input.value.trim();
        if (!text) return;
        input.value = '';
        this.appendChat('user', text);
        // 存在挂起的自主目标会话：消息直接作为该会话的补充（缺参时自然语言补参）。
        if (this.autonomousPanel.hasActiveSession()) {
            this.autonomousPanel.chatToSession(text);
            return;
        }
        // 占位气泡先出现，答案到达后在同一气泡内打字机流式渲染。
        const bubble = this.appendChatStreaming('…正在解析（受控 Agent 流程）', null);
        try {
            const reply = await this.api('/wavepilot/chat', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ conversationId: this.conversationId, message: text })
            });
            if (reply && reply.conversationId) {
                this.conversationId = reply.conversationId;
                try { sessionStorage.setItem('wavepilot.conversationId', reply.conversationId); }
                catch (ignored) { }
            }
            // ChatResponse 的正文在 answer 字段；其他字段只做状态标注，不整包贴出。
            const answer = reply && reply.answer ? String(reply.answer)
                : reply && (reply.response || reply.message || reply.content)
                    ? String(reply.response || reply.message || reply.content)
                    : '（收到无法解析的服务端回复，请查看服务日志）';
            const modeNote = reply && typeof reply.mockRunner === 'boolean'
                ? (reply.mockRunner ? '\n\n[当前为模拟模式（Mock Runner），结果不是真实 MATLAB 仿真]'
                    : '\n\n[当前为真实模式（真实 MATLAB Runner）]') : '';
            this.streamIntoChat(bubble, answer + modeNote, () => {
                // 行动型请求：后端已启动受控 Goal 会话，流式渲染结束后接管其执行轨迹轮询。
                if (reply.goalSessionId) {
                    this.autonomousPanel.adoptSession(reply.goalSessionId);
                }
            });
        } catch (error) {
            bubble.innerHTML = renderMarkdown('解析失败：' + error.message
                + '（缺参追问与自然语言解析需要模型服务；结构化 Spec 可直接在高级区域提交）');
        }
    }

    appendChat(role, text) {
        const div = document.createElement('div');
        div.className = 'chat-msg ' + role;
        // user 输入保持纯文本（不渲染用户内容）；assistant 输出按 markdown 渲染。
        if (role === 'assistant') {
            div.innerHTML = renderMarkdown(text);
        } else {
            div.textContent = text;
        }
        this.chatMessages.appendChild(div);
        this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
        this.saveChatState();
    }

    /** 聊天历史存入 sessionStorage：刷新/切页后恢复渲染（不跨会话记忆）。 */
    saveChatState() {
        try {
            const messages = [];
            for (const el of this.chatMessages.querySelectorAll('.chat-msg')) {
                messages.push({
                    role: el.classList.contains('user') ? 'user' : 'assistant',
                    text: el.textContent
                });
            }
            sessionStorage.setItem('wavepilot.chatHistory', JSON.stringify(messages.slice(-50)));
        } catch (ignored) { /* storage unavailable */ }
    }

    /** 页面加载时恢复聊天历史（assistant 重新走 markdown 渲染）。 */
    restoreChatState() {
        try {
            const raw = sessionStorage.getItem('wavepilot.chatHistory');
            if (!raw) return;
            const messages = JSON.parse(raw);
            if (!Array.isArray(messages)) return;
            for (const msg of messages) {
                this.appendChat(msg.role === 'user' ? 'user' : 'assistant',
                    msg.text || '');
            }
        } catch (ignored) { /* malformed history */ }
    }

    /** 创建一条 assistant 气泡并打字机式输出；短文本直接显示，返回气泡元素。 */
    appendChatStreaming(text, onDone) {
        const div = document.createElement('div');
        div.className = 'chat-msg assistant';
        this.chatMessages.appendChild(div);
        this.streamIntoChat(div, text, onDone);
        return div;
    }

    /** 向已存在的气泡做打字机式输出：每帧增量渲染 markdown（完整行渲染、末行挂起），
     * 完成后整段重渲染一次并回调。 */
    streamIntoChat(div, text, onDone) {
        const total = String(text).length;
        if (total <= 40) {
            div.innerHTML = renderMarkdown(text);
            if (onDone) onDone();
            return;
        }
        // 总时长约 1.2s–2.4s，按长度自适应步长，避免过长回答久等。
        const steps = Math.max(40, Math.min(80, Math.round(total / 12)));
        const chunk = Math.ceil(total / steps);
        let i = 0;
        const timer = setInterval(() => {
            i = Math.min(i + chunk, total);
            div.innerHTML = renderMarkdownStreaming(String(text).slice(0, i));
            this.chatMessages.scrollTop = this.chatMessages.scrollHeight;
            if (i >= total) {
                clearInterval(timer);
                div.innerHTML = renderMarkdown(text);
                this.saveChatState();
                if (onDone) onDone();
            }
        }, 30);
    }

    /* ---------------- 知识上传 ---------------- */
    async uploadKnowledge() {
        const file = this.$('knowledgeFile').files[0];
        if (!file) { this.toast('请先选择知识文档', true); return; }
        const form = new FormData();
        form.append('file', file);
        // 后端契约需要 documentType/experimentType/title/source/version 必填参数；
        // 工作台上传统一归类为理论文档，其余元数据按文件名/固定值补齐。
        form.append('documentType', 'THEORY');
        form.append('experimentType', 'POLAR_CODE_K_IDENTIFICATION');
        form.append('title', file.name.replace(/\.[^.]+$/, ''));
        form.append('source', 'workbench');
        form.append('version', '1.0');
        try {
            const result = await fetch(this.apiBaseUrl + '/wavepilot/knowledge/upload', { method: 'POST', body: form });
            const body = await result.json().catch(() => ({}));
            this.$('knowledgeUploadResult').textContent =
                result.ok ? '上传成功，文档已入库（' + (body.message || '可开始检索') + '）'
                    : '上传失败：' + (body.message || result.status);
        } catch (error) {
            this.$('knowledgeUploadResult').textContent = '上传失败：' + error.message;
        }
    }

    /* ---------------- 中间栏：Spec 校验与任务创建 ---------------- */
    currentSpec() {
        return JSON.parse(this.specJson.value);
    }

    async validateSpec() {
        try {
            const spec = this.currentSpec();
            const result = await this.api('/experiments/spec/parse', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(spec)
            });
            this.specValidationResult.textContent = result.valid
                ? 'Java 校验通过（提示：' + (result.warnings || []).join('；') + '）'
                : 'Java 校验未通过：' + (result.errors || []).join('；')
                    + '（字段名对照：codeLengths=码长，errorRate=错误率，sampleCount=样本数，monteCarloTimes=重复次数）';
        } catch (error) {
            this.specValidationResult.textContent = '校验失败：' + error.message;
        }
    }

    async createJob() {
        try {
            const spec = this.currentSpec();
            const job = await this.api('/experiments', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(spec)
            });
            this.selectedJobId = job.jobId;
            const plan = job.plan || {};
            this.planPreview.textContent = '实验计划：' + (plan.planId || '')
                + ' | 模板：' + (plan.experimentTemplateVersion || '')
                + ' | 参数点：' + (plan.totalRuns || 0)
                + ' | 阶段：' + (plan.stages || []).map(stageLabel).join(' → ');
            this.toast('任务已创建：' + job.jobId);
            this.refreshJobs();
            // selectJob 内部会按任务状态决定是否打开唯一的 SSE 连接；这里不再重复打开，
            // 避免双 EventSource 互相打断导致"已连接/中断"反复提示。
            this.selectJob(job.jobId);
        } catch (error) {
            this.planPreview.textContent = '创建失败：' + error.message;
            this.jobError.textContent = '创建失败：' + error.message;
        }
    }

    /* ---------------- Job 列表、状态、SSE ---------------- */
    async refreshJobs() {
        try {
            this.jobs = await this.api('/experiments');
            this.renderJobList();
        } catch (error) {
            this.jobError.textContent = '任务列表加载失败：' + error.message;
        }
    }

    renderJobList() {
        this.jobList.innerHTML = '';
        if (!this.jobs.length) {
            this.jobList.textContent = '（暂无任务）';
            return;
        }
        for (const job of this.jobs) {
            const item = document.createElement('div');
            item.className = 'job-item' + (job.jobId === this.selectedJobId ? ' selected' : '');
            item.innerHTML = '<span class="job-status ' + job.status + '">' + statusLabel(job.status)
                + '</span> ' + job.jobId
                + (job.sourceJobId ? ' ← 由 ' + job.sourceJobId + ' 复现' : '');
            item.addEventListener('click', () => this.selectJob(job.jobId));
            this.jobList.appendChild(item);
        }
    }

    async selectJob(jobId) {
        this.selectedJobId = jobId;
        this.closeSse();
        this.jobError.textContent = '';
        this.renderJobList();
        try {
            const job = await this.api('/experiments/' + jobId);
            this.jobStatusLine.textContent = '状态：' + statusLabel(job.status)
                + (job.failureReason ? ' | 失败原因：' + job.failureReason : '');
            const templateId = this.$('contextTemplateId');
            if (templateId && job.plan && job.plan.experimentTemplateVersion) {
                templateId.textContent = job.plan.experimentTemplateVersion;
            }
            const progress = await this.api('/experiments/' + jobId + '/progress');
            this.progressBar.style.width = progress.progress + '%';
            this.jobStage.textContent = '阶段：' + stageLabel(progress.currentStage) + ' | ' + progress.message;
            this.currentPointLine.textContent = '当前参数点：' + progress.completedRuns + ' / ' + progress.totalRuns;
            this.jobBoundaryBadge.className = 'boundary-badge boundary-unknown';
            this.jobBoundaryBadge.textContent = '任务边界：读取产物中…';
            await this.loadArtifacts(jobId);
            if (job.status === 'RUNNING' || job.status === 'QUEUED'
                    || job.status === 'CREATED' || job.status === 'VALIDATED') {
                this.openSse(jobId);
            } else {
                this.currentPointLine.textContent = '当前参数点：' + (progress.completedRuns || 0)
                    + ' / ' + (progress.totalRuns || 0) + '（任务已终止，最终状态 ' + statusLabel(job.status) + '）';
            }
        } catch (error) {
            this.jobStatusLine.textContent = '任务不存在或读取失败：' + error.message;
            this.jobError.textContent = '任务不存在或读取失败：' + error.message;
        }
    }

    openSse(jobId) {
        this.closeSse();
        const emitter = new EventSource(this.apiBaseUrl + '/experiments/' + jobId + '/stream');
        this.sse = emitter;
        emitter.addEventListener('progress', (event) => {
            try {
                const progress = JSON.parse(event.data);
                this.progressBar.style.width = progress.progress + '%';
                this.jobStage.textContent = '阶段：' + stageLabel(progress.currentStage) + ' | ' + progress.message;
                this.currentPointLine.textContent = '当前参数点：' + progress.completedRuns
                    + ' / ' + progress.totalRuns + '（SSE 实时）';
                if (progress.status) {
                    this.jobStatusLine.textContent = '状态：' + statusLabel(progress.status);
                    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(progress.status)) {
                        this.closeSse();
                        this.refreshJobs();
                        this.loadArtifacts(jobId);
                    }
                }
            } catch (ignored) { /* malformed SSE frame */ }
        });
        // 任务不存在或服务重启后运行态记录已失效：服务端以 SSE 事件告知。
        // 此时必须关闭连接、停止自动重连、清除失效状态并提示，而不是无限重连。
        emitter.addEventListener('job-not-found', () => {
            this.closeSse();
            this.selectedJobId = null;
            this.jobStatusLine.textContent = '该任务不存在或服务重启后运行态记录已失效';
            this.jobError.textContent = '该任务不存在或服务重启后运行态记录已失效';
            this.progressBar.style.width = '0%';
            this.jobStage.textContent = '';
            this.currentPointLine.textContent = '—';
            this.jobBoundaryBadge.className = 'boundary-badge boundary-unknown';
            this.jobBoundaryBadge.textContent = '任务边界：未知';
            this.renderJobList();
        });
        emitter.onerror = () => {
            // CLOSED：服务端已正常关闭（任务不存在/任务完成），不提示重连；
            // CONNECTING：真正的网络短暂中断，EventSource 会自动重连，限流提示
            // （10 秒内只提示一次），且绝不把任务状态写成 SUCCEEDED。
            if (emitter.readyState === EventSource.CLOSED) return;
            const now = Date.now();
            if (!this.lastSseErrorAt || now - this.lastSseErrorAt > 10000) {
                this.lastSseErrorAt = now;
                this.toast('SSE 连接中断，正在自动重连…（任务状态以 REST 查询为准）');
            }
        };
        emitter.onopen = () => {
            if (!this.sseNotified) {
                this.sseNotified = true;
                this.toast('SSE 已连接：' + jobId);
            }
        };
    }

    closeSse() {
        if (this.sse) {
            this.sse.close();
            this.sse = null;
        }
        this.sseNotified = false;
    }

    async cancelJob() {
        if (!this.selectedJobId) { this.toast('请先选择任务', true); return; }
        try {
            const job = await this.api('/experiments/' + this.selectedJobId + '/cancel', { method: 'POST' });
            this.jobStatusLine.textContent = '状态：' + statusLabel(job.status) + '（已请求取消）';
            this.toast('已请求取消：' + this.selectedJobId);
            this.closeSse();
            this.refreshJobs();
        } catch (error) {
            this.jobError.textContent = '取消失败：' + error.message;
        }
    }

    /* ---------------- Artifact ---------------- */
    async loadArtifacts(jobId) {
        try {
            this.artifacts = await this.api('/experiments/' + jobId + '/artifacts');
            this.renderArtifacts();
            this.updateBoundaryFromArtifacts();
        } catch (error) {
            this.artifactList.textContent = '产物加载失败：' + error.message;
        }
    }

    updateBoundaryFromArtifacts() {
        const record = this.artifacts.find(item => item.validated);
        if (!record) {
            this.renderBoundary(this.jobBoundaryBadge, null, null, null, null);
            this.renderBoundary(this.globalBoundaryBadge, null, null, null, null);
            return;
        }
        this.renderBoundary(this.jobBoundaryBadge, record.mock,
            record.algorithmValidated, record.classification, record.runnerType);
        this.renderBoundary(this.globalBoundaryBadge, record.mock,
            record.algorithmValidated, record.classification, record.runnerType);
    }

    renderArtifacts() {
        this.artifactList.innerHTML = '';
        this.artifactMetadata.textContent = '';
        this.pngPreview.innerHTML = '';
        if (!this.artifacts.length) {
            this.artifactList.textContent = '（暂无产物）';
            return;
        }
        for (const artifact of this.artifacts) {
            const item = document.createElement('div');
            item.className = 'artifact-item';
            item.id = 'artifact-' + artifact.artifactId;
            const suffix = artifact.validated ? '' : ' [未验证]';
            item.innerHTML = '<strong>' + artifactTypeLabel(artifact.artifactType) + '</strong> '
                + artifact.fileName + suffix + '<br>SHA-256：' + artifact.sha256;
            item.addEventListener('click', () => this.showArtifact(artifact));
            this.artifactList.appendChild(item);
        }
    }

    async showArtifact(artifact) {
        this.artifactMetadata.textContent =
            '产物编号：' + artifact.artifactId + '\n'
            + '类型：' + artifactTypeLabel(artifact.artifactType) + '\n'
            + '文件名：' + artifact.fileName + '\n'
            + '相对路径：' + artifact.relativePath + '\n'
            + 'SHA-256：' + artifact.sha256 + '\n'
            + '大小：' + artifact.size + ' 字节\n'
            + 'MIME 类型：' + artifact.mimeType + '\n'
            + '已通过校验：' + yesNo(artifact.validated) + '\n'
            + '运行器：' + artifact.runnerType + '\n'
            + '模拟实验：' + yesNo(artifact.mock) + '\n'
            + '算法类别：' + artifact.classification + '\n'
            + '算法已验证：' + yesNo(artifact.algorithmValidated);
        try {
            const verified = await this.api('/artifacts/' + artifact.artifactId + '/verify', { method: 'POST' });
            if (!verified.valid) {
                this.toast('Artifact 校验失败（哈希或大小已变化）：' + artifact.artifactId, true);
            }
        } catch (error) {
            this.toast('Artifact 失效：' + error.message, true);
        }
        if (artifact.mimeType && artifact.mimeType.includes('png')) {
            this.previewPng(artifact.artifactId);
        }
        if (artifact.mimeType && artifact.mimeType.includes('csv')) {
            this.artifactMetadata.textContent += '\nCSV 文件下载：' + this.apiBaseUrl + '/artifacts/'
                + artifact.artifactId + '/download';
        }
    }

    async previewPng(artifactId) {
        try {
            const response = await fetch(this.apiBaseUrl + '/artifacts/' + artifactId + '/download');
            if (!response.ok) throw new Error('download HTTP ' + response.status);
            const blob = await response.blob();
            const url = URL.createObjectURL(blob);
            // 新版工作台中 pngPreview 就是 <img> 元素本身。
            this.pngPreview.hidden = false;
            this.pngPreview.src = url;
            this.pngPreview.alt = 'accuracy curve preview';
        } catch (error) {
            this.pngPreview.hidden = true;
            this.pngPreview.src = '';
            this.artifactMetadata.textContent += '\nPNG 预览失败：' + error.message;
        }
    }

    /* ---------------- 报告与 Citation ---------------- */
    async generateReport() {
        if (!this.selectedJobId) { this.toast('请先选择任务', true); return; }
        this.reportError.textContent = '';
        try {
            await this.api('/experiments/' + this.selectedJobId + '/report', { method: 'POST' });
            await this.renderReport();
            await this.loadCitations();
            this.resultReportState.textContent = '已生成 · 引用已验证';
        } catch (error) {
            this.resultReportState.textContent = '生成失败';
            this.reportError.textContent = '报告生成失败：' + error.message
                + '（报告需要任务 SUCCEEDED 且全部来源 Artifact 通过校验）';
        }
    }

    async renderReport() {
        const report = await this.api('/experiments/' + this.selectedJobId + '/report');
        // 与聊天区共用同一渲染层（转义 + markdown + 链接白名单）。
        this.reportContent.innerHTML = renderMarkdown(report.markdown || '');
        const generator = report.generatedBy === 'REPORT_AGENT' ? '报告模型'
            : report.generatedBy === 'TEMPLATE_FALLBACK' ? '模板报告（模型失败回退）'
            : '模板报告（' + report.generatedBy + '）';
        const footer = document.createElement('div');
        footer.className = 'report-footer';
        footer.textContent = '【生成方式：' + generator
            + ' | 引用状态：' + (report.status === 'VERIFIED' ? '已验证（VERIFIED）' : report.status) + '】';
        this.reportContent.appendChild(footer);
    }

    async validateReport() {
        if (!this.selectedJobId) { this.toast('请先选择任务', true); return; }
        try {
            const result = await this.api('/experiments/' + this.selectedJobId + '/report/validate',
                { method: 'POST' });
            this.reportError.textContent = result.valid ? 'Citation 重新校验通过'
                : 'Citation 重新校验失败：' + (result.errors || []).join('; ');
            this.resultReportState.textContent = result.valid ? '已生成 · 引用已验证' : '引用校验失败';
        } catch (error) {
            this.resultReportState.textContent = '引用校验失败';
            this.reportError.textContent = '校验失败：' + error.message;
        }
    }

    async loadCitations() {
        if (!this.selectedJobId) return;
        this.citationList.innerHTML = '';
        const citations = await this.api('/experiments/' + this.selectedJobId + '/citations');
        if (!citations.length) {
            this.citationList.textContent = '（暂无 Citation）';
            return;
        }
        for (const citation of citations) {
            const item = document.createElement('div');
            item.className = 'citation-item';
            item.innerHTML = '<span class="citation-status">' + citationStatusLabel(citation) + '</span> '
                + '<strong>' + citation.citationId + '</strong> '
                + '字段 ' + citation.fieldName + (citation.rowReference ? '，行 ' + citation.rowReference : '')
                + ' = ' + JSON.stringify(citation.value)
                + ' <button class="secondary" data-citation-artifact="' + citation.artifactId
                + '">定位 Artifact</button>';
            item.querySelector('button').addEventListener('click',
                () => this.locateArtifact(citation.artifactId));
            this.citationList.appendChild(item);
        }
    }

    locateArtifact(artifactId) {
        const target = document.getElementById('artifact-' + artifactId);
        if (target) {
            target.scrollIntoView({ behavior: 'smooth', block: 'center' });
            target.style.outline = '2px solid #0f2744';
        } else {
            this.toast('Artifact 不在当前列表：' + artifactId);
        }
    }

    /* ---------------- Replay ---------------- */
    async startReplay() {
        if (!this.selectedJobId) { this.toast('请先选择任务', true); return; }
        this.replayError.textContent = '';
        try {
            const record = await this.api('/experiments/' + this.selectedJobId + '/replay', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ note: 'workbench replay' })
            });
            this.toast('Replay 已启动：' + record.replayId);
            this.pollReplay(record.replayId);
        } catch (error) {
            this.replayError.textContent = 'Replay 失败：' + error.message
                + '（源任务必须 SUCCEEDED 且 Artifact 哈希有效）';
        }
    }

    async pollReplay(replayId) {
        for (let attempt = 0; attempt < 300; attempt++) {
            await new Promise(resolve => setTimeout(resolve, 200));
            try {
                const record = await this.api('/replays/' + replayId);
                if (record.status !== 'RUNNING') {
                    await this.renderReplayComparison(record);
                    this.refreshReplays();
                    return;
                }
            } catch (ignored) { /* replay not ready yet */ }
        }
        this.replayError.textContent = 'Replay 轮询超时：' + replayId;
    }

    async renderReplayComparison(record) {
        if (record.status === 'FAILED') {
            this.replayComparison.textContent = 'Replay 失败：' + record.failureReason;
            return;
        }
        try {
            const comparison = await this.api('/replays/' + record.replayId + '/comparison');
            const verdict = comparison.verdict === 'REPRODUCIBLE'
                ? '可复现（REPRODUCIBLE）'
                : comparison.verdict === 'NOT_REPRODUCIBLE'
                    ? '不可复现（NOT_REPRODUCIBLE）' : comparison.verdict;
            let text = 'Replay ' + record.replayId + ' | 判定：' + verdict
                + ' | 严格一致性检查：' + yesNo(comparison.consistent)
                + ' | 数值容差内：' + yesNo(comparison.withinTolerance)
                + '\n说明：' + comparison.message;
            for (const metric of comparison.metrics) {
                text += '\n指标 ' + metricLabel(metric.metricName) + '（'
                    + (metric.present ? '可比较' : '不可比较')
                    + '）：最大绝对差=' + (metric.maxAbsDifference == null ? '无数据' : metric.maxAbsDifference)
                    + (metric.meanAbsDifference == null ? '' : '，平均绝对差=' + metric.meanAbsDifference)
                    + '，容差内：' + yesNo(metric.withinTolerance);
            }
            // Replay 一致是复现判定，不是算法科研验证；边界由 manifest 保留。
            text += '\n注意：Replay 一致不代表算法已验证（algorithmValidated=false 始终保留）。';
            this.replayComparison.textContent = text;
        } catch (error) {
            this.replayComparison.textContent = 'Replay 对比结果读取失败：' + error.message;
        }
    }

    async refreshReplays() {
        try {
            this.replays = await this.api('/replays');
            this.renderReplayList();
        } catch (error) {
            this.replayList.textContent = 'Replay 列表加载失败：' + error.message;
        }
    }

    renderReplayList() {
        this.replayList.innerHTML = '';
        if (!this.replays.length) {
            this.replayList.textContent = '（暂无 Replay）';
            return;
        }
        for (const record of this.replays) {
            const item = document.createElement('div');
            item.className = 'replay-item';
            item.innerHTML = '<strong>' + statusLabel(record.status) + '</strong> ' + record.replayId
                + ' | 源任务：' + record.sourceJobId
                + (record.replayJobId ? ' | 新任务：' + record.replayJobId : '')
                + (record.failureReason ? ' | ' + record.failureReason : '');
            item.addEventListener('click', () => this.renderReplayComparison(record));
            this.replayList.appendChild(item);
        }
    }

    /* ---------------- Eval ---------------- */
    async runEval(modelName) {
        this.evalError.textContent = '';
        try {
            const run = await this.api('/evaluations/run', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ datasetName: 'default', modelName: modelName })
            });
            this.lastEvalIds.push(run.evaluationId);
            if (this.lastEvalIds.length > 2) this.lastEvalIds.shift();
            this.renderEvalReport(run);
        } catch (error) {
            this.evalError.textContent = 'Eval 运行失败：' + error.message;
        }
    }

    renderEvalReport(run) {
        // passedCases() 是服务端方法而非字段，不会出现在 JSON 里；由前端按结果自行统计。
        const passed = (run.results || []).filter(result => result.passed).length;
        const modelNote = run.modelName === 'stub-v1' ? '（脚本化参考模型）'
            : run.modelName === 'stub-v2' ? '（脚本化缺陷模型，用于回归演示）' : '';
        let text = 'Eval 编号：' + run.evaluationId + ' | 模型：' + run.modelName + modelNote
            + ' | 通过：' + passed + '/' + run.results.length
            + ' | 状态：' + statusShort(run.status);
        for (const metric of run.metrics) {
            text += '\n[' + metricLabel(metric.metricName) + '] ' + (metric.value * 100).toFixed(1)
                + '%（' + metric.numerator + '/' + metric.denominator + '）';
        }
        this.evalMetrics.textContent = text;
        const failed = run.results.filter(result => !result.passed);
        this.evalFailedCases.innerHTML = failed.length
            ? failed.map(result => '<div class="error-text">❌ ' + result.caseId + '（'
                + result.caseType + '）：' + (result.failureReason || result.actualResult) + '</div>').join('')
            : '<div class="technical-block">全部 Case 通过（stub-v1 参考模型）</div>';
    }

    async compareEvals() {
        if (this.lastEvalIds.length < 2) {
            this.evalComparison.textContent = '需要先运行两次 Eval（例如 stub-v1 与 stub-v2）再比较。';
            return;
        }
        try {
            const baseline = this.lastEvalIds[this.lastEvalIds.length - 2];
            const candidate = this.lastEvalIds[this.lastEvalIds.length - 1];
            const comparison = await this.api('/evaluations/compare', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ baselineEvaluationId: baseline, candidateEvaluationId: candidate })
            });
            let text = '基准（Baseline）：' + baseline + ' → 候选（Candidate）：' + candidate
                + ' | 允许发布：' + yesNo(comparison.releaseAllowed) + '\n' + comparison.message;
            for (const delta of comparison.metricDeltas) {
                text += '\n[' + metricLabel(delta.metricName) + '] 基准=' + (delta.baselineValue * 100).toFixed(1)
                    + '% 候选=' + (delta.candidateValue * 100).toFixed(1)
                    + '% 差值=' + (delta.delta * 100).toFixed(1) + '%';
            }
            text += '\n退化 Case：' + (comparison.regressedCaseIds.length
                ? comparison.regressedCaseIds.join(', ') : '无');
            text += '\n新增通过 Case：' + (comparison.newlyPassedCaseIds.length
                ? comparison.newlyPassedCaseIds.join(', ') : '无');
            this.evalComparison.textContent = text;
        } catch (error) {
            this.evalComparison.textContent = '比较失败：' + error.message;
        }
    }
}

function citationStatusLabel(citation) {
    // CitationStatus 只表示引用完整性（VERIFIED/PARTIAL/UNVERIFIED），不是概率。
    return '引用状态：' + (citation.artifactSha256 ? '已验证（VERIFIED）' : '未验证（UNVERIFIED）');
}

/* ============================================================
 * 自主模式面板：启动会话 → 1s 轮询时间线 → 在人工挂起点弹窗。
 * 护栏说明：模型无法自行填参或批准发布；WAITING_PARAMS /
 * WAITING_APPROVAL 只能通过这里的人工操作继续。
 * ============================================================ */
class AutonomousPanel {
    constructor(workbench) {
        this.wb = workbench;
        this.apiBaseUrl = workbench.apiBaseUrl;
        this.sessionId = null;
        this.pollTimer = null;
        this.polling = false;
        this.dialogOpen = false;   // 同一时间只允许一个挂起弹窗
        this.lastRenderedSteps = 0;
        this.initElements();
        this.bindEvents();
    }

    initElements() {
        this.toggle = document.getElementById('autonomousToggle');
        this.requestInput = document.getElementById('autonomousRequest');
        this.startBtn = document.getElementById('autonomousStartBtn');
        this.cancelBtn = document.getElementById('autonomousCancelBtn');
        this.analyzeToggle = document.getElementById('autonomousAnalyzeToggle');
        this.statusLine = document.getElementById('autonomousStatusLine');
        this.timeline = document.getElementById('autonomousTimeline');
        this.errorLine = document.getElementById('autonomousError');
        this.goalSummary = document.getElementById('agentGoalSummary');
        this.inlineActions = document.getElementById('agentInlineActions');
        // 新版工作台用右侧 Approval Drawer（旧 autonomousApprovalDialog 已降级为 compat 空壳）。
        this.drawer = document.getElementById('agentApprovalDrawer');
        this.approvalBody = document.getElementById('autonomousApprovalBody');
        this.approvalError = document.getElementById('autonomousApprovalError');
        this.currentStatus = null;
        // 同一批参数只追问一次（模型解析失败会再次挂起，避免刷屏）。
        this.lastParamAsk = null;
    }

    bindEvents() {
        // 新版 UI 中执行轨迹（autonomousPanel）常驻，不再由开关隐藏。
        this.startBtn.addEventListener('click', () => this.start());
        this.cancelBtn.addEventListener('click', () => this.cancel());
        document.getElementById('autonomousApproveBtn')
            .addEventListener('click', () => this.submitApproval(true));
        document.getElementById('autonomousRejectBtn')
            .addEventListener('click', () => this.submitApproval(false));
        // 用户关闭缺参弹窗：会话仍挂起，下轮轮询会重新提示继续。
        document.getElementById('paramFillDialog').addEventListener('close', () => {
            this.dialogOpen = false;
        });
        // 用户手动关闭审批 Drawer：会话仍挂起，下轮轮询会重新打开提示。
        document.getElementById('approvalDrawerClose')
            .addEventListener('click', () => { this.dialogOpen = false; });
    }

    async start() {
        const request = this.requestInput.value.trim();
        if (!request) { this.wb.toast('请先输入一句话描述实验需求', true); return; }
        await this.startWithRequest(request);
    }

    /** 统一入口：聊天发送后由 workbench 调用，不再依赖独立的自主模式开关。 */
    async startWithRequest(request) {
        this.errorLine.textContent = '';
        this.timeline.innerHTML = '';
        this.lastRenderedSteps = 0;
        this.closeDrawer();
        try {
            const session = await this.api('/autonomous/start', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    request: request,
                    analyzeResults: this.analyzeToggle.checked
                })
            });
            this.sessionId = session.sessionId;
            this.cancelBtn.disabled = false;
            this.statusLine.textContent = '目标会话已启动：' + session.sessionId
                + '（模型 ' + session.modelName + '）';
            this.saveActiveGoal();
            this.startPolling();
        } catch (error) {
            this.errorLine.textContent = '启动失败：' + error.message;
        }
    }

    /** 活跃目标会话写入 sessionStorage：刷新页面后能重新接管轮询。 */
    saveActiveGoal() {
        try {
            sessionStorage.setItem('wavepilot.activeGoal', this.sessionId || '');
        } catch (ignored) { /* storage unavailable */ }
    }

    clearActiveGoal() {
        try { sessionStorage.removeItem('wavepilot.activeGoal'); } catch (ignored) { }
    }

    /** 页面加载时恢复活跃目标会话（服务端会话仍在内存，未重启时不会丢）。 */
    async restoreActiveGoal() {
        let sessionId = null;
        try { sessionId = sessionStorage.getItem('wavepilot.activeGoal') || null; }
        catch (ignored) { }
        if (!sessionId) return;
        try {
            const session = await this.api('/autonomous/' + sessionId);
            if (session && !this.isTerminal(session.status)) {
                this.adoptSession(sessionId);
            } else {
                this.clearActiveGoal();
            }
        } catch (error) {
            // 会话不存在（服务重启或已清理）：清除本地记录，回到空闲。
            this.clearActiveGoal();
        }
    }

    /** 会话是否仍在推进（非终态）；聊天消息优先补充给当前会话。 */
    hasActiveSession() {
        return !!this.sessionId && this.currentStatus !== null
            && !this.isTerminal(this.currentStatus);
    }

    /** 后端统一入口已启动 goal 会话（/wavepilot/chat 返回 goalSessionId 时）。 */
    adoptSession(sessionId) {
        this.sessionId = sessionId;
        this.currentStatus = null;
        this.timeline.innerHTML = '';
        this.lastRenderedSteps = 0;
        this.errorLine.textContent = '';
        this.cancelBtn.disabled = false;
        this.statusLine.textContent = '目标会话：' + sessionId;
        this.saveActiveGoal();
        this.startPolling();
    }

    /** 会话挂起时用户的聊天消息：缺参 → 自然语言补参；待审批 → 引导使用审批面板。 */
    async chatToSession(text) {
        if (this.currentStatus === 'WAITING_PARAMS') {
            await this.submitParams(this.sessionId, { rawText: text });
            return;
        }
        if (this.currentStatus === 'WAITING_APPROVAL') {
            this.appendChat('assistant', '该候选模板正在等待你的批准。请使用右侧「批准并继续」或「拒绝」完成人工审批；'
                + 'Agent 不会代替你发布模板。');
            return;
        }
        this.appendChat('assistant', '（目标正在自动推进中，Agent 会在需要你介入时提示。）');
    }

    async cancel() {
        if (!this.sessionId) return;
        try {
            await this.api('/autonomous/' + this.sessionId + '/cancel', { method: 'POST' });
            this.stopPolling();
            this.clearActiveGoal();
            this.wb.toast('已请求取消自主会话');
        } catch (error) {
            this.errorLine.textContent = '取消失败：' + error.message;
        }
    }

    /* ---------------- 轮询与渲染 ---------------- */
    startPolling() {
        if (this.polling) return;
        this.polling = true;
        const tick = async () => {
            if (!this.polling) return;
            try {
                const session = await this.api('/autonomous/' + this.sessionId);
                this.render(session);
                if (this.isTerminal(session.status)) {
                    this.finish(session);
                    return;
                }
                this.handleSuspension(session);
            } catch (error) {
                this.errorLine.textContent = '轮询失败：' + error.message;
            }
            this.pollTimer = setTimeout(tick, 1000);
        };
        tick();
    }

    stopPolling() {
        this.polling = false;
        if (this.pollTimer) {
            clearTimeout(this.pollTimer);
            this.pollTimer = null;
        }
    }

    finish(session) {
        this.stopPolling();
        this.currentStatus = session.status;
        this.cancelBtn.disabled = true;
        this.startBtn.disabled = false;
        this.requestInput.disabled = false;
        this.closeDrawer();
        this.clearActiveGoal();
        this.statusLine.textContent = autonomousStatusLabel(session.status)
            + (session.error ? '（' + session.error + '）' : '');
        this.statusLine.className = 'status-pill neutral';
        this.updateGoalSummary(session);
        if (session.status === 'SUCCEEDED') {
            // Agent 分析结果（模型基于 analyzeResult 读取的真实仿真指标的总结）输出到对话框；
            // 模板报告正文保留在「结果与证据」页，聊天里只给编号，不替代 Agent 分析。
            if (session.analysis) {
                this.appendAnalysisStep(session.analysis);
                this.appendChatStreaming('📊 Agent 分析结果\n\n' + session.analysis, null);
            }
            this.appendChat('assistant', '目标已完成' + (session.reportId
                ? '，报告 ' + session.reportId + ' 已生成（正文见「结果与证据」页）' : '') + '。');
            this.wb.toast('目标执行成功' + (session.reportId ? '，报告 ' + session.reportId : ''));
            this.wb.refreshJobs();
            this.wb.refreshTemplates();
        } else if (session.status === 'FAILED' || session.status === 'CANCELLED') {
            this.appendChat('assistant', '目标' + (session.status === 'FAILED' ? '执行失败' : '已取消')
                + (session.error ? '：' + session.error : '。'));
        }
    }

    /** 仿真结束后的 Agent 分析汇报，以系统步骤追加到时间线尾部。 */
    appendAnalysisStep(analysis) {
        const div = document.createElement('div');
        div.className = 'timeline-step';
        const badge = document.createElement('span');
        badge.className = 'tiny-badge';
        badge.textContent = 'Agent 分析结果';
        const body = document.createElement('div');
        body.className = 'technical-block';
        body.style.cssText = 'margin-top:5px;white-space:pre-wrap';
        body.textContent = analysis;
        div.appendChild(badge);
        div.appendChild(body);
        this.timeline.appendChild(div);
        this.timeline.scrollTop = this.timeline.scrollHeight;
    }

    /** 向主聊天区追加一条消息（统一显示在 chatMessages），复用 workbench 的渲染层。 */
    appendChat(role, text) {
        this.wb.appendChat(role, text);
    }

    /** 向主聊天区流式输出（打字机 + markdown），供最终汇报使用。 */
    appendChatStreaming(text, onDone) {
        return this.wb.appendChatStreaming(text, onDone);
    }

    /** 挂起点：只有这些状态需要等人；模型无法绕过。缺参优先在 Chat 内追问（fallback 表单仍可用）。 */
    handleSuspension(session) {
        this.currentStatus = session.status;
        if (this.dialogOpen) return;
        if (session.status === 'WAITING_PARAMS') {
            this.dialogOpen = true;
            this.askParametersInChat(session);
        } else if (session.status === 'WAITING_APPROVAL') {
            this.dialogOpen = true;
            this.openApproval(session);
        }
    }

    /** 缺参：在聊天区追问（逐参数带描述/单位/默认值 + 所属模板版本），用户可直接回复
     * 自然语言；复杂 Schema 仍保留参数表单作为 fallback。
     * 同一批参数只追问一次——模型解析失败会再次挂起，此时只打开表单并简短提示，不刷屏。 */
    askParametersInChat(session) {
        const pending = session.pendingParams || {};
        const parameters = pending.parameters || [];
        const names = parameters.filter(p => p.required).map(p => p.name).join('、');
        const signature = this.sessionId + '|' + names;
        if (this.lastParamAsk === signature) {
            // 已追问过同一批参数：说明上次补充未被解析，直接引导使用表单，不再重复发消息。
            this.appendChat('assistant', '（上次补充的参数没有被解析出来，请直接用下方表单填写，'
                + '或在回复里按字段名给值，如 codeLengths=32,64）');
            this.wb.openAutonomousParamDialog(session);
            return;
        }
        this.lastParamAsk = signature;
        const header = (pending.templateDisplayName ? '模板：' + pending.templateDisplayName
            : pending.templateId ? '模板：' + pending.templateId : '（新建模板候选）')
            + (pending.version ? '（v' + pending.version + '）' : '')
            + '\n请补充以下实验参数：';
        const detail = parameters.map(p => '• ' + p.name
            + (p.required ? '（必填）' : '')
            + (p.unit ? ' [' + p.unit + ']' : '')
            + (p.defaultValue !== undefined && p.defaultValue !== null && p.defaultValue !== ''
                ? '，默认 ' + p.defaultValue : '')
            + (p.description ? '：' + p.description : '')).join('\n');
        const message = header + '\n' + (detail || '（' + parameters.length + ' 项）')
            + '\n你可以直接在下方回复，例如 "0–10 dB，100000 帧"；系统会把这些补充合并进当前目标。';
        this.appendChat('assistant', message);
        // fallback：复杂 Schema 时也打开参数表单（仅收集参数值，不改变对话为主交互）。
        this.wb.openAutonomousParamDialog(session);
    }

    render(session) {
        this.currentStatus = session.status;
        this.statusLine.textContent = autonomousStatusLabel(session.status);
        this.statusLine.className = 'status-pill neutral';
        this.updateGoalSummary(session);
        const steps = session.steps || [];
        if (steps.length <= this.lastRenderedSteps) return;
        const fragment = document.createDocumentFragment();
        for (let i = this.lastRenderedSteps; i < steps.length; i++) {
            fragment.appendChild(this.renderStep(steps[i]));
        }
        this.lastRenderedSteps = steps.length;
        this.timeline.appendChild(fragment);
        this.timeline.scrollTop = this.timeline.scrollHeight;
    }

    /** 右侧「当前任务意图」摘要：状态、模板、任务、报告。 */
    updateGoalSummary(session) {
        if (!this.goalSummary) return;
        const pending = session.pendingParams || {};
        let text = '目标：' + String(session.request || '').slice(0, 90);
        text += '\n状态：' + autonomousStatusLabel(session.status);
        if (pending.templateId) text += '\n模板：' + pending.templateId + (pending.version ? ' ' + pending.version : '');
        if (pending.candidateId) text += '\n候选：' + pending.candidateId;
        if (session.jobId) text += '\n任务：' + session.jobId;
        if (session.reportId) text += '\n报告：' + session.reportId;
        this.goalSummary.textContent = text;
    }

    renderStep(step) {
        const div = document.createElement('div');
        div.className = 'timeline-step';
        const badge = document.createElement('span');
        badge.className = 'tiny-badge muted';
        const time = document.createElement('span');
        time.style.cssText = 'color:var(--faint);font-size:9px;margin-left:6px';
        time.textContent = this.formatTime(step.timestamp);
        const body = document.createElement('div');
        body.className = 'technical-block';
        body.style.cssText = 'margin-top:5px;white-space:pre-wrap';
        if (step.role === 'model') {
            badge.textContent = '模型思考';
            body.textContent = step.message || '';
        } else if (step.role === 'tool') {
            badge.textContent = '工具：' + (step.toolName || '');
            body.textContent = step.toolResult || '';
        } else if (step.role === 'user') {
            badge.textContent = '用户';
            body.textContent = step.message || '';
        } else {
            badge.textContent = '系统';
            body.textContent = step.message || '';
        }
        const text = body.textContent;
        if (text.length > 800) {
            body.textContent = text.slice(0, 800) + '\n…（已截断，共 ' + text.length + ' 字符）';
            div.title = '完整内容见服务端会话记录';
        }
        div.appendChild(badge);
        div.appendChild(time);
        div.appendChild(body);
        return div;
    }

    formatTime(timestamp) {
        if (!timestamp) return '';
        try {
            return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false });
        } catch (ignored) {
            return '';
        }
    }

    isTerminal(status) {
        return status === 'SUCCEEDED' || status === 'FAILED'
            || status === 'CANCELLED' || status === 'BLOCKED';
    }

    /* ---------------- 人工操作 ---------------- */
    async submitParams(sessionId, params) {
        this.dialogOpen = false;
        try {
            await this.api('/autonomous/' + sessionId + '/params', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ params: params })
            });
            this.wb.toast('参数已提交，自主流程继续…');
        } catch (error) {
            this.wb.toast('参数提交失败：' + error.message, true);
            this.errorLine.textContent = '参数提交失败：' + error.message;
        }
    }

    /** 审批：右侧 Drawer + 聊天区 Inline Action Card；批准必须填写审批人标识。 */
    openApproval(session) {
        const pending = session.pendingParams || {};
        this.approvalError.textContent = '';
        this.renderApprovalCard(session, pending);
        this.renderApprovalDrawer(session, pending);
        this.drawer.classList.add('open');
        this.drawer.setAttribute('aria-hidden', 'false');
        this.appendChat('assistant', '候选模板已准备好发布：'
            + (pending.templateId || '') + ' ' + (pending.version || '')
            + '\n✓ 安全检查  ✓ Schema 校验  ✓ MATLAB Smoke\n'
            + '模板运行验证：' + (pending.realSmokeExecuted ? '通过' : '未执行（Fake Smoke）')
            + '　算法科学验证：未完成\n是否批准发布？（Agent 不会代替你批准）');
    }

    /** 聊天区内的审批操作卡。 */
    renderApprovalCard(session, pending) {
        const card = document.createElement('div');
        card.className = 'agent-action-card';
        const text = document.createElement('div');
        text.textContent = '候选 ' + (pending.candidateId || '')
            + '（' + (pending.templateId || '') + ' ' + (pending.version || '') + '）已完成：'
            + '\n✓ 安全检查  ✓ Schema 校验  ✓ MATLAB Smoke  ✓ Result Contract'
            + '\n模板运行验证：' + (pending.realSmokeExecuted ? '通过' : '未执行')
            + '　算法科学验证：未完成';
        const row = document.createElement('div');
        row.className = 'button-row';
        const approve = document.createElement('button');
        approve.className = 'btn btn-primary';
        approve.textContent = '批准并继续';
        approve.addEventListener('click', () => this.submitApproval(true));
        const reject = document.createElement('button');
        reject.className = 'btn btn-danger-soft';
        reject.textContent = '拒绝';
        reject.addEventListener('click', () => this.submitApproval(false));
        row.appendChild(approve);
        row.appendChild(reject);
        card.appendChild(text);
        card.appendChild(row);
        this.inlineActions.innerHTML = '';
        this.inlineActions.appendChild(card);
    }

    /** 右侧审批 Drawer：安全检查/Smoke 摘要 + 审批人输入。 */
    renderApprovalDrawer(session, pending) {
        this.approvalBody.innerHTML = '';
        const summary = document.createElement('div');
        summary.className = 'technical-block';
        const findings = pending.securityFindings || [];
        const blocked = findings.filter(f => f.severity === 'BLOCKED');
        const warnings = findings.filter(f => f.severity === 'WARNING');
        summary.textContent = '候选：' + (pending.candidateId || '')
            + '\n模板：' + (pending.templateId || '') + ' 版本 ' + (pending.version || '')
            + '\n状态：' + candidateStatusLabel(pending.status)
            + '\nSmoke：' + (pending.realSmokeExecuted ? '已真实执行' : 'MATLAB Smoke 未执行')
            + (pending.smokeReport ? '\n' + pending.smokeReport : '')
            + '\n安全检查：BLOCKED ' + blocked.length + ' 项，WARNING ' + warnings.length + ' 项';
        this.approvalBody.appendChild(summary);
        for (const finding of findings) {
            const div = document.createElement('div');
            div.className = 'technical-block';
            div.style.cssText = 'margin-top:6px;border:1px solid '
                + (finding.severity === 'BLOCKED' ? 'var(--danger-soft)' : 'var(--warning-soft)');
            div.textContent = '[' + finding.severity + '] ' + (finding.ruleId || '') + ' @ '
                + (finding.file || '') + ':' + (finding.line != null ? finding.line : '')
                + (finding.message ? ' — ' + finding.message : '');
            this.approvalBody.appendChild(div);
        }
        const row = document.createElement('div');
        row.className = 'param-row';
        row.style.marginTop = '10px';
        const label = document.createElement('label');
        label.textContent = '审批人 *';
        const input = document.createElement('input');
        input.id = 'autonomousApprovedBy';
        input.placeholder = '你的标识（将记录在发布历史中）';
        input.value = 'user';
        row.appendChild(label);
        row.appendChild(input);
        this.approvalBody.appendChild(row);
    }

    closeDrawer() {
        if (this.drawer) {
            this.drawer.classList.remove('open');
            this.drawer.setAttribute('aria-hidden', 'true');
        }
        if (this.inlineActions) this.inlineActions.innerHTML = '';
    }

    async submitApproval(approved) {
        const body = { approved: approved };
        if (approved) {
            const approvedBy = document.getElementById('autonomousApprovedBy').value.trim();
            if (!approvedBy) {
                this.approvalError.textContent = '批准必须提供审批人标识';
                return;
            }
            body.approvedBy = approvedBy;
        }
        this.dialogOpen = false;
        this.closeDrawer();
        try {
            await this.api('/autonomous/' + this.sessionId + '/approval', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            });
            this.wb.toast(approved ? '已批准发布，目标继续执行…' : '已拒绝候选，目标结束');
        } catch (error) {
            this.dialogOpen = false;
            this.wb.toast('审批提交失败：' + error.message, true);
            this.errorLine.textContent = '审批提交失败：' + error.message;
        }
    }

    async api(path, options) {
        const response = await fetch(this.apiBaseUrl + path, options);
        if (!response.ok) {
            let message = 'HTTP ' + response.status;
            try {
                const body = await response.json();
                if (body && body.message) message = body.message;
            } catch (ignored) { /* non-JSON error body */ }
            throw new Error(message);
        }
        return response.json();
    }
}

window.addEventListener('DOMContentLoaded', () => {
    window.workbench = new WavePilotWorkbench();
    // 刷新/切页后恢复：聊天历史重新渲染，未终态的活跃目标会话重新接管轮询。
    // 服务端会话在内存中，项目未重启则记录仍在。
    window.workbench.restoreChatState();
    window.workbench.autonomousPanel.restoreActiveGoal();
});
