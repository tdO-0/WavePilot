# 模板生成与发布流程

更新时间：2026-08-06

## 一句话总结

用户用自然语言描述新实验 → 系统生成 **Candidate Template**（候选包）→ 静态安全校验 → （可选）MATLAB Smoke → **用户显式批准** → 原子发布为 **ACTIVE** 正式模板。Agent 永远不能自行发布。

## 主流程

```text
用户自然语言
  -> TemplateGenerationService（Stub/外部模型）
       -> candidates/<candidateId>/（definition.yaml + manifest + matlab/run_experiment.m + README）
       -> DRAFT -> GENERATED -> VALIDATING
  -> CandidateValidationService（安全扫描 + 定义校验 + manifest 一致性 + 哈希完整性）
       -> 通过 -> SMOKE_PENDING
       -> 失败 -> VALIDATION_FAILED（可修复重试）
       -> 声明式无法表达 -> REQUIRES_CUSTOM_EXTENSION
  -> CandidateSmokeService
       -> 有真实 MATLAB（template-smoke profile）-> SMOKE_PASSED / SMOKE_FAILED
       -> 无 MATLAB（默认 Fake）-> 不伪造通过 -> REVIEW_REQUIRED（报告写明"MATLAB Smoke 未执行"）
  -> 用户显式批准（approve + approvedBy）
       -> 复查（安全无 BLOCKED、哈希未篡改、定义有效、无版本冲突、algorithmValidated=false）
       -> 临时目录 -> 重算哈希 -> publication-record.json -> 原子移动到 approved/<templateId>/<version>/
       -> TemplateRegistry.registerApproved -> ACTIVE
       -> ExperimentDefinitionRegistry.register（无需修改任何 Java Map）
```

## 状态机

13 个状态（DRAFT/GENERATED/VALIDATING/VALIDATION_FAILED/SMOKE_PENDING/SMOKE_PASSED/SMOKE_FAILED/REVIEW_REQUIRED/APPROVED/ACTIVE/REJECTED/ARCHIVED/ROLLED_BACK + REQUIRES_CUSTOM_EXTENSION）。非法迁移（如 GENERATED→APPROVED、SMOKE_PENDING→APPROVED）由 `CandidateStateMachine` 拒绝。

## 发布保证

- 发布前复查：安全检查无 BLOCKED；候选文件哈希与校验时一致（篡改即拒）；定义 schema 有效；模板版本无冲突；`algorithmValidated` 必须保持 false（除非存在独立人工验证 reference，当前一律拒绝）。
- 原子发布：临时目录写入 → 重算哈希 → 原子移动 → 注册。已发布版本**不可原地修改**，升级必须创建新版本。
- 回滚只切换 activeVersion，不删除任何历史文件。
- 未执行真实 Smoke 时，发布允许但 `operationalValidated=false`，前端与 API 明确显示"MATLAB Smoke 未执行"。
- 发布记录（publication-record.json）含 publicationId/candidateId/approvedBy/approvedAt/哈希/source/algorithmValidated/previousVersion。

## 未完成项

- `template-smoke` profile 的真实 MATLAB 候选 Smoke 执行器已预留接口（CandidateSmokeRunner），真实实现未在默认环境运行验证；`external-template-generation` profile 的真实模型生成器同样未验证。未运行时不得声称已通过。
