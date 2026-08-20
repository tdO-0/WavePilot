# 自主 Agent 模式（受控 ReAct 循环）

WavePilot 的自主模式让用户用一句话描述实验需求，由模型（qwen3.7-max，无模型时回退到脚本化 Stub）按固定执行剧本自主推进：查模板 → 收集参数 → 候选/直接建实验 → 校验 → Smoke → 审批 → 等仿真结束 → 生成报告。每一步（模型思考、工具调用、工具结果）都写入会话时间线，前端实时滚动；**只有"需要人"的两个点会弹窗**：填参数、审批发布。

本文档说明受控循环的架构、护栏、人工干预点，以及与手动操作的安全等价性。

## 一、核心架构：受控 ReAct 循环

自主模式不使用 `ReactAgent` 黑盒，而是自己实现模型-工具循环（`AutonomousSessionService`）：

```
每轮：模型输出一个 JSON 工具调用
  → 会话层白名单校验（AutonomousToolExecutor.WHITELIST）
  → 会话层执行（复用 TemplateRegistry / CandidateValidationService /
     CandidateSmokeService / TemplatePublishingService / ExperimentService /
     ReportService 等既有服务）
  → 记录 AutonomousStep 到时间线
  → 结果写回会话 chatHistory
  → 检查挂起点 / 终态 → 继续或挂起
```

- 模型每轮只能输出一个工具调用 JSON：`{"tool": "<工具名>", "arguments": {...}}`。
- 工具白名单（模型无权调用白名单之外的任何工具）：

  `searchTemplates, getTemplateDetail, requestParameterInput, submitSpec, generateCandidate, validateCandidate, smokeCandidate, requestTemplateApproval, waitForJobCompletion, generateReport, finish`

- 会话最多 50 轮；连续 3 轮输出无法解析、或连续 3 轮调用越权工具，会话自动终止（FAILED）并提示用户介入。

## 二、执行剧本（AutonomousAgentPrompt.SYSTEM_PROMPT）

模型被约束按固定顺序推进，不得跳过任何安全检查：

1. `searchTemplates` 查询是否有匹配的已发布模板；没有时进入候选流程。
2. 实验所需参数（Eb/N0 范围、步长、帧数、码长等）**不得编造**：调用 `requestParameterInput` 提供参数名（和模板 ID，如果有），等待用户填写。
3. 有可用模板：调用 `submitSpec` 提交实验（参数来自用户填写结果）。
4. 没有可用模板：依次调用 `generateCandidate → validateCandidate → smokeCandidate`，然后调用 `requestTemplateApproval` 请用户审批。
5. 用户批准后调用 `submitSpec` 提交实验；拒绝时输出 `finish` 说明。
6. 提交实验后必须调用 `waitForJobCompletion` 等待仿真结束（不猜测状态）。
7. 实验成功后调用 `generateReport` 生成报告，然后 `finish`。

## 三、人工干预点（模型没有能力绕过）

会话层强制挂起，模型调用工具只能"请求"挂起，不能自行完成：

| 挂起点 | 触发工具 | 会话状态 | 前端 | 继续方式 |
|---|---|---|---|---|
| 缺参数 | `requestParameterInput` | `WAITING_PARAMS` | 参数对话框（含每个参数的 必填/类型/单位/说明/建议值） | `POST /api/autonomous/{id}/params` |
| 候选发布 | `requestTemplateApproval` | `WAITING_APPROVAL` | 审批对话框（展示安全检查 BLOCKED/WARNING、Smoke 报告、真实执行标志），批准必须填写审批人标识 | `POST /api/autonomous/{id}/approval` |

- 工具集里没有审批/激活工具：候选需要发布时，模型只能调用 `requestTemplateApproval` → 会话层挂起 → 用户批准后由会话层调用 `TemplatePublishingService.approveAndPublish`。
- 参数值由用户在对话框填写，服务端用定义（或候选定义）组装 `ExperimentSpec`；模型拿到的只是用户填写的值，不能自行发明。
- 挂起期间模型循环暂停；提交后自动 resume。

## 四、会话状态机

```
ANALYZING → CHECKING_TEMPLATE →（缺参）WAITING_PARAMS → …
→（无模板）GENERATING_CANDIDATE → VALIDATING → SMOKING → WAITING_APPROVAL → …
→ RUNNING_EXPERIMENT → GENERATING_REPORT → SUCCEEDED
终止：FAILED / CANCELLED（用户取消）/ BLOCKED（保留，预留阻断）
```

- `waitForJobCompletion`：会话层以 300ms 间隔轮询 `ExperimentService.progress` 直到终态（最长 10 分钟），模型调用一次即拿到最终结果。
- `cancel`：任意非终态下可取消，取消立即生效（下一轮循环开始前检查）。

## 五、与手动操作的安全等价性

| 操作 | 手动工作台 | 自主模式 | 等价性 |
|---|---|---|---|
| 参数填写 | 模板参数对话框（用户填写） | 同一对话框逻辑（用户填写） | 同一用户输入，服务端组装 Spec |
| 候选校验/Smoke | `validateCandidate` / `smokeCandidate` 按钮 | 剧本强制调用，模型不得跳过 | 同一 `CandidateValidationService` / `CandidateSmokeService` |
| 候选发布 | 用户显式批准（填审批人） | 会话层挂起 → 用户批准（填审批人） | 同一 `TemplatePublishingService.approveAndPublish`，模型无权调用 |
| 建实验 | 中间栏 Spec + 创建任务 | `submitSpec`（spec 由服务端从用户参数组装） | 同一 `ExperimentService.create` |
| 报告 | 生成报告按钮 | `generateReport` | 同一 `ReportService.generate` |

## 六、护栏汇总（会话层强制，模型无法绕过）

1. **工具白名单**：每轮工具调用过白名单校验，未授权工具拒绝并提示重试；连续 3 次越权自动终止。
2. **挂起点**：缺参/审批只能通过 `requestParameterInput` / `requestTemplateApproval` 进入，只能通过控制器端点由用户提交继续。
3. **参数不可编造**：spec 由服务端从用户填写的值组装；模型没有构造 spec 的工具（`submitSpec` 只接受会话内携带的 specJson）。
4. **安全检查不可跳过**：剧本强制 `validateCandidate → smokeCandidate → requestTemplateApproval` 顺序。
5. **解析护栏**：模型输出必须是可解析的工具调用 JSON，连续失败自动终止。
6. **轮数上限**：50 轮封顶，防止失控循环。

## 七、真实模型的概率性说明

模型是概率性的，可能偶尔不按剧本行事（跳过步骤、乱调工具、输出解释而非 JSON）。会话层会：

- 拒绝越权调用并提示纠正；
- 解析失败时把错误喂回模型要求重试；
- 连续跑偏自动终止并请你介入。

这不是"完全放开"，而是"模型在护栏里自主"。真实模型的行为不承诺 100% 遵循剧本；护栏保证的是：**任何越权/跳过安全步骤的行为都会被拒绝或终止**，人工干预点永远由人决定。

## 八、离线与测试

- 无 `ChatModel` 时回退到 `AutonomousStubModel`（脚本化流程走查），因此默认 `mvn -B clean test` 完全离线，不依赖外部模型。
- `AutonomousFlowTest` 覆盖：无模板 → 候选 → 审批 → 实验 → 报告；有模板直接建实验；审批拒绝结束会话；取消。
- 测试栈按生产方式接线：`ApprovedTemplateDefinitionLoader` 从磁盘恢复已发布定义（等价于生产重启），Mock Runner 在声明式模板下按定义 `requiredColumns` 写 CSV（等价于真实 MATLAB 脚本输出契约），`ResultValidator` 注入同一 `ExperimentDefinitionRegistry`。

## 九、REST 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/autonomous/start` | `{request}` 启动会话，返回会话（含 sessionId） |
| GET | `/api/autonomous` | 会话列表（按创建时间倒序） |
| GET | `/api/autonomous/{sessionId}` | 会话详情（含时间线 steps、挂起点 pendingParams） |
| POST | `/api/autonomous/{sessionId}/params` | `{params: {...}}` 提交参数，会话继续 |
| POST | `/api/autonomous/{sessionId}/approval` | `{approved, approvedBy?}` 批准/拒绝发布 |
| POST | `/api/autonomous/{sessionId}/cancel` | 取消会话 |

会话时间线每步：`role`（model/tool/system/user）、`message`、`toolName`、`toolResult`、`status`、`timestamp`。前端以 1s 轮询渲染；`WAITING_PARAMS` / `WAITING_APPROVAL` 弹窗复用模板参数对话框与审批对话框。
