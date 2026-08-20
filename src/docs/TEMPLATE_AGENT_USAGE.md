# Agent 模板用法

更新时间：2026-08-06

## 受控工具（7 个）

| 工具 | 作用 |
|---|---|
| listExperimentTemplates | 回答"目前系统里有哪些模板"：templateId、experimentTypeId、activeVersion、来源、状态、可运行性、algorithmValidated |
| getExperimentTemplate | 模板详情：参数、输出、指标、Replay、版本、验证边界 |
| listTemplateCandidates | 候选列表与生命周期状态 |
| getTemplateCandidate | 候选详情：安全检查、Smoke 报告、假设、待确认问题 |
| generateTemplateCandidate | 一句话生成候选包（**只是候选**） |
| validateTemplateCandidate | 触发静态校验 |
| requestTemplateSmoke | 请求 MATLAB Smoke（无环境时报告未执行） |

## 对话示例

用户："目前系统里有哪些模板？"
Agent：调用 `listExperimentTemplates`，回答极化码 K 识别等模板的版本、状态、是否真实可运行、algorithmValidated 状态。

用户："帮我新增一个 QPSK-AWGN BER 模板。"
Agent：调用 `generateTemplateCandidate`，返回 candidateId、生成状态、假设项、下一步校验提示。

用户："这个候选模板怎么样？"
Agent：调用 `getTemplateCandidate`，解释安全检查、Smoke 状态与待确认问题，并**提醒用户去审批**（Agent 无权自行发布）。

## 禁止（Agent 无对应工具）

- approveTemplateCandidate / activateTemplate / publishTemplateCandidate / rejectTemplateCandidate / deleteTemplate —— 不存在
- 修改正式模板、绕过安全检查或 Smoke、把 algorithmValidated 改为 true
- 直接调用 ProcessBuilder、直接访问模板目录、直接写文件系统
