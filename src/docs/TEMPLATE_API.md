# 模板目录 API

更新时间：2026-08-06

前缀 `/api/wavepilot`。

## 正式模板

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /templates | 模板列表；过滤参数：source、status、experimentTypeId、classification、operationalValidated、algorithmValidated |
| GET | /templates/{templateId} | 详情：激活版本、版本历史、参数/输出/指标/Replay 定义、算法元数据 |
| GET | /templates/{templateId}/versions/{version} | 指定版本 |
| POST | /templates/{templateId}/deactivate | 停用（body 空） |
| POST | /templates/{templateId}/rollback | 回滚到历史 ACTIVE 版本，body `{"version":"..."}`；只切换 activeVersion，不删除历史 |

## 候选模板

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /template-candidates | 候选列表（含状态/安全检查/Smoke 状态） |
| GET | /template-candidates/{candidateId} | 候选详情 |
| POST | /template-candidates/generate | body `{"request":"自然语言描述"}` → 生成候选包 |
| POST | /template-candidates/{candidateId}/validate | 静态校验（安全扫描/定义/清单/哈希） |
| POST | /template-candidates/{candidateId}/smoke | 请求 Smoke；无真实 MATLAB 时报告"未执行"并进入待审批 |
| POST | /template-candidates/{candidateId}/approve | **用户显式审批**，body `{"approvedBy":"用户标识"}`；发布为 ACTIVE |
| POST | /template-candidates/{candidateId}/reject | 拒绝，body `{"reason":"原因"}` |

## 响应保证

- 所有 JSON 不暴露绝对路径、不返回密钥/环境变量/模型鉴权信息。
- 模板记录含：templateId、experimentTypeId、displayName、version、source（BUILT_IN/AGENT_GENERATED/USER_IMPORTED-预留）、status、classification、operationalValidated、algorithmValidated、createdAt、publishedAt、definitionSha256、templateSha256、activeVersion、supportedParameters、outputArtifacts。
- 候选记录含 securityFindings（ruleId/severity/file/line/message/evidence）与 smokeReport/realSmokeExecuted。
