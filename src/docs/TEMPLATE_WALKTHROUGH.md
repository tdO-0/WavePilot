# 模板生成与发布实操指南（带着走）

更新时间：2026-08-06

这份文档带你**实际操作一遍**"用一句话添加一个新实验模板"的完整流程。每一步有：**做什么** → **命令/操作** → **预期返回** → **怎么看**。

全程约 3 分钟（无需 MATLAB/DashScope，默认 Stub 生成模型 + Fake Smoke）。

---

## 0. 流程总览

```text
① 查看现有模板（系统里有什么）
② 一句话生成候选模板（Agent / API / 前端三选一）
③ 校验候选（安全检查 + 定义 + 清单 + 哈希）
④ 请求 Smoke（无 MATLAB 时明确显示"未执行"）
⑤ 查看候选详情（安全、Smoke、假设、待确认问题）
⑥ 用户显式批准（必须填写审批人）→ 原子发布 → ACTIVE
⑦ 验证：模板出现在列表、重启后仍在、详情完整
```

> 关键边界：①-⑤ Agent 都能做，⑥ 只能由用户做。**Agent 无权自行发布。**

---

## ① 查看现有模板

**API**：

```bash
curl -s http://localhost:9900/api/wavepilot/templates | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{JSON.parse(r).forEach(t=>console.log(t.displayName,'|',t.templateId,'| 版本',t.activeVersion,'| 来源',t.source,'| 可运行',t.operationalValidated,'| 算法已验证',t.algorithmValidated))})"
```

**预期**：两个内置模板（极化码码维数识别 / 集成 fixture），来源 `BUILT_IN`，`可运行 true`（真实 MATLAB smoke 跑过），`算法已验证 false`。

**前端**：右侧"模板库"→"刷新模板"。**Agent**：问"目前系统里有哪些模板？"。

---

## ② 一句话生成候选模板

**API**：

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/template-candidates/generate \
  -H "Content-Type: application/json" \
  -d '{"request":"新增一个 QPSK 在 AWGN 信道下的 BER 仿真模板，Eb/N0 从 0 到 12 dB，输出仿真 BER、理论 BER 和曲线"}' \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('candidateId:',d.candidateId);console.log('templateId:',d.templateId);console.log('状态:',d.status);console.log('假设:',d.assumptions);console.log('待确认:',d.unresolvedQuestions)})"
```

**预期**：返回 `candidateId`（如 `CAND-XXXX`）、`templateId=qpsk-awgn-ber`、状态 `SMOKE_PENDING`（静态校验已通过，等待 Smoke）。

**这一步做了什么**：Stub 生成模型产出候选包（definition.yaml + manifest + matlab/run_experiment.m + README），服务立即做路径规范化、大小限制、定义 schema 校验、manifest 一致性、SHA-256——**全部通过后才进入 SMOKE_PENDING**。此时它只是候选，**不能**创建实验任务。

**前端**：模板库输入框输入描述 → "生成候选模板"（自动接着校验和请求 Smoke）。**Agent**：说"帮我新增一个 QPSK-AWGN BER 模板"。

---

## ③ 校验候选（可以重复执行）

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/template-candidates/CAND-XXXX/validate \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('状态:',d.status);console.log('安全发现:',d.securityFindings);console.log('失败原因:',d.failureReason||'无')})"
```

**预期**：状态 `SMOKE_PENDING`，`securityFindings: []`（Stub 模板不含危险调用）。

**怎么看**：`securityFindings` 是分层结果——BLOCKED 会直接导致 `VALIDATION_FAILED`；WARNING 需要人工审查；空 = 通过。任何 BLOCKED（如模板里出现 `system()`、`eval()`、`webread()`、`delete()`）都进不了 Smoke。

---

## ④ 请求 Smoke

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/template-candidates/CAND-XXXX/smoke \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('状态:',d.status);console.log('真实 Smoke 已执行:',d.realSmokeExecuted);console.log('Smoke 报告:',d.smokeReport)})"
```

**预期**（无真实 MATLAB 环境）：状态 `REVIEW_REQUIRED`，`realSmokeExecuted=false`，报告明确写着 **"MATLAB Smoke 未执行"**。

> 关键：没有真实 MATLAB 时系统**绝不伪造 SMOKE_PASSED**，候选直接进入"待人工审批"，前端显示"MATLAB Smoke 未执行"。

---

## ⑤ 查看候选详情（发布前最后确认）

```bash
curl -s http://localhost:9900/api/wavepilot/template-candidates/CAND-XXXX \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('状态:',d.status);console.log('安全检查:',d.securityFindings.length,'项');console.log('Smoke:',d.realSmokeExecuted?'已执行':'未执行');console.log('假设:',d.assumptions);console.log('待确认:',d.unresolvedQuestions)})"
```

**前端**：点击候选列表项 → 详情面板显示安全 BLOCKED/WARNING 计数、Smoke 报告、假设、待确认问题、算法验证边界（`algorithmValidated=false`）。

---

## ⑥ 用户显式批准并发布（关键一步）

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/template-candidates/CAND-XXXX/approve \
  -H "Content-Type: application/json" \
  -d '{"approvedBy":"你的名字或工号"}' \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('状态:',d.status);console.log('模板:',d.templateId,d.version)})"
```

**预期**：状态 `ACTIVE`。发布前服务自动复查：安全无 BLOCKED、文件哈希未篡改、定义有效、无版本冲突、`algorithmValidated=false` 保持。然后临时目录 → 重算哈希 → 写 `publication-record.json`（含 approvedBy/时间/哈希）→ 原子移动到 `data/wavepilot/templates/approved/<templateId>/<version>/` → 注册 ACTIVE。

> 没有 `approvedBy` 或空白 → 直接拒绝（"Approval requires an explicit approver identity"）。**Agent 工具集里没有 approve**，这一步只能通过 REST 或前端按钮。

**前端**：选候选 → "批准发布" → 弹出审批人输入框（不填就取消）。

---

## ⑦ 验证发布成功

```bash
echo "=== 模板列表 ==="
curl -s http://localhost:9900/api/wavepilot/templates | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{JSON.parse(r).forEach(t=>console.log(t.displayName,'|',t.templateId,'| 版本',t.activeVersion,'| 来源',t.source,'| 可运行',t.operationalValidated,'| 算法已验证',t.algorithmValidated))})"
echo "=== 模板详情（参数/输出/指标/Replay）==="
curl -s http://localhost:9900/api/wavepilot/templates/qpsk-awgn-ber \
  | node -e "let r='';process.stdin.on('data',c=>r+=c);process.stdin.on('end',()=>{const d=JSON.parse(r);console.log('激活版本:',d.active.activeVersion);console.log('参数:',d.definition.parameters.map(p=>p.name+(p.required?'*':'')).join(', '));console.log('输出列:',d.definition.outputs.requiredColumns.join(', '));console.log('指标:',d.definition.metrics.map(m=>m.displayName).join(', '));console.log('Replay:',d.definition.replay.map(m=>m.comparisonColumn+' 容差'+m.maxAbsoluteTolerance).join(', '))})"
```

**预期**：
- 列表里出现 `qpsk-awgn-ber`（来源 `AGENT_GENERATED`，可运行 `false`——因为没有真实 Smoke，算法已验证 `false`）；
- 详情含 4 个参数、3 个输出列、1 个指标、1 个 Replay 比较列。

> `operationalValidated=false` 是诚实的：没有真实 MATLAB Smoke，就不能声称"可运行"。将来配了 MATLAB 跑通 `template-smoke` 后重新走一遍流程，这条才会变 true。

---

## 重启持久化验证

```bash
# 重启服务后
curl -s http://localhost:9900/api/wavepilot/templates | grep -c qpsk-awgn-ber
```

**预期**：`1`——发布记录和模板文件都在 `data/wavepilot/templates/`（registry.json + approved/），重启后自动重新加载。

---

## 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| approve 返回 422 "requires an explicit approver identity" | 没传 approvedBy | 补上审批人 |
| approve 返回 "Version conflict" | 同 templateId+version 已发布且不可变 | 生成新版本号重新走流程 |
| approve 返回 "hash mismatch" | 候选文件被篡改 | 重新 generate 或 validate 修复 |
| validate 返回 VALIDATION_FAILED + BLOCKED | 模板含高危 MATLAB 调用 | 修改模板内容重新生成 |
| Smoke 显示"未执行" | 无真实 MATLAB 环境 | 正常；需真实 Smoke 用 template-smoke profile |
| 模板详情里可运行=false | 无真实 Smoke | 诚实展示，不冒充已验证 |
