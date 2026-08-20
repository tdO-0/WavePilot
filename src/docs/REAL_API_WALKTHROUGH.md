# WavePilot 真实 API 全流程实操指南

更新时间：2026-08-06

本文带你把一个真实的通信仿真问题从"想法"一路跑到"报告 + 复现 + 评价"的完整闭环。每一步都有：**这一步在做什么** → **执行命令** → **预期返回** → **怎么看结果**。

全程约 15~30 分钟（取决于 MATLAB 规模）。

---

## 0. 流程总览

```text
你的仿真问题
  └─ ① 明确实验参数（码长、错误率、样本数…）
  └─ ② 上传通信知识文档（RAG 语料，Agent 检索用）
  └─ ③ Agent 自然语言理解 → 缺参追问 → ExperimentSpec（真实模型）
  └─ ④ Java 确定性校验（模型输出不可信，必须过这关）
  └─ ⑤ 实验计划预览 + 创建异步任务（状态机）
  └─ ⑥ 观察执行：REST 轮询 / SSE 实时进度
  └─ ⑦ 产物登记与校验（SHA-256 + verify）
  └─ ⑧ 生成带 Citation 的报告（数值可追溯到源文件）
  └─ ⑨ Replay 复现（同配置重跑 + REPRODUCIBLE 判定）
  └─ ⑩ Eval 评价（24 个固定 Case + Baseline/Candidate 对比）
  └─ ⑪ 回到工作台界面核对全过程
```

每步的真实性标注：

| 环节 | 真实（需外部环境） | 可降级（离线仍可跑） |
|---|---|---|
| 自然语言→Spec、Agent 对话 | 真实 DashScope 模型 | ✗（无模型只能走结构化 JSON） |
| 知识上传与检索 | 真实 Milvus + DashScope Embedding | 内存知识库 + 离线 Embedding |
| 实验执行 | 本机 MATLAB（13 列真实契约） | Mock Runner（3 列简版契约） |
| 校验/报告/Citation/Replay/Eval | 全部是真实 Java 代码，无降级 | 同左 |

> 重要：Mock Runner 的简版契约**不能生成报告**（报告需要 13 列真实契约）。要体验报告环节，实验执行必须走真实 MATLAB。下文以"真实模式"为主线，降级处会标注。

---

## 1. 准备环境

### 1.1 需要的东西

- JDK 17+、Maven 3.9+（已有）
- **真实 DASHSCOPE_API_KEY**（第 3 步必需；Embedding 用它）
- **本机 MATLAB**（第 6 步执行真实实验；`D:\Program Files\MATLAB\R2023b\bin\matlab.exe`）
- Milvus 可选（不装就降级为内存知识库，检索效果只用于演示）

### 1.2 启动服务（真实模式）

```powershell
$env:DASHSCOPE_API_KEY = "<你的真实密钥>"
$env:WAVEPILOT_RUNNER_TYPE = "local-matlab"
$env:MATLAB_EXECUTABLE = "D:\Program Files\MATLAB\R2023b\bin\matlab.exe"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"     # 没有 Milvus 就 memory
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"          # 配套离线向量（Milvus 模式下不要设）
$env:WAVEPILOT_EVAL_JOB_WAIT_MILLIS = "300000"     # 真实 MATLAB 下 Eval 的 Job Case 等待上限（5 分钟）
mvn spring-boot:run
```

> 没有 MATLAB 时：去掉 RUNNER_TYPE/MATLAB_EXECUTABLE 两行（默认 Mock），并跳过第 8 步报告环节。
> `WAVEPILOT_EVAL_JOB_WAIT_MILLIS` 很关键：Eval 的 Job/Replay Case 默认只等 10 秒（为毫秒级 Mock 设计），真实 MATLAB 一次执行约 15-20 秒，不调大这些 Case 会以 `WAIT_TIMEOUT` 失败（任务本身仍在跑，不是故障）。

### 1.3 确认服务起来了

```bash
curl -s http://localhost:9900/api/experiments | head
```

预期：`[]`（还没有任何任务）。**这一步的作用**：确认服务与 API 可用，作为后续所有请求的基座。

---

## 2. 第一步：明确你的仿真问题

当前平台只支持 **极化码码维数识别（POLAR_CODE_K_IDENTIFICATION）**，你的问题落到参数上就是一组实验规格：

| 参数 | 含义 | 本示例取值 |
|---|---|---|
| codeLengths | 码长 N（2 的幂，32~512） | [32, 64] |
| errorRateStart / End / Step | BSC 误码率扫描范围 | 0 → 0.02，步长 0.01 |
| sampleCount | 每点拦截完整码字数 M | 20 |
| monteCarloTimes | 每点独立重复次数 T | 10 |
| randomSeed | 随机种子（保证可复现） | 20 |

**示例问题**："我想知道极化码在 N=32 和 64、BSC 误码率 0 到 0.02 下的码维数 K 识别准确率。"

后续所有步骤都用这组参数。你也可以换成 128/256/512，但**不能**换实验类型。

---

## 3. 第二步：准备并上传通信知识文档

### 3.1 这一步在做什么

知识库是 Agent 的参考书：上传极化码理论、实验配方、失败案例等文档后，Agent 检索相关内容来回答你的问题、构造 Spec。文档会被切分、向量化并带 metadata（文档类型、实验类型、版本）入库。

### 3.2 准备文档

新建 `D:\demo\polar-notes.md`（内容示例，请用你自己的笔记也行）：

```markdown
# 极化码码维数识别实验笔记
极化码由信道极化构造，生成矩阵 G 由 N 次 Kronecker 幂得到，N 为 2 的幂。
信息位集合按 BEC 可靠性排序选择；码维数 K 由信道容量决定。
识别方法：收到完整码字后做逆向极化变换，对信息位做逐位判决，
再按二项式对数似然估计 K。BSC 误码率越高，估计方差越大。
```

### 3.3 上传

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/knowledge/upload \
  -F "file=@D:/demo/polar-notes.md" \
  -F "documentType=THEORY" \
  -F "experimentType=POLAR_CODE_K_IDENTIFICATION" \
  -F "title=极化码码维数识别实验笔记" \
  -F "source=demo-notes" \
  -F "version=1.0.0"
```

预期返回：

```json
{"documentId":"...","chunkCount":1,"documentType":"THEORY","experimentType":"POLAR_CODE_K_IDENTIFICATION","title":"极化码码维数识别实验笔记",...}
```

**怎么看结果**：`chunkCount` 是该文档被切成几片。`documentId` 后面检索/失败排查要用。

### 3.4 验证检索命中（这一步的作用：确认知识真的进库了，Agent 之后能搜到）

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/knowledge/search \
  -H "Content-Type: application/json" \
  -d '{"query":"极化码 码维数 识别 生成矩阵","topK":3}'
```

预期：第一条结果的 `content` 包含"生成矩阵"，`citation` 形如 `KB[...]`。

> 降级提示：内存知识库 + 离线 Embedding 下检索也能工作（确定性向量），只是相似度排序质量不如真实 Embedding；`similarityScore` 只作排序用，不是概率。

---

## 4. 第三步：让 Agent 理解需求（真实模型）

### 4.1 这一步在做什么

把你的自然语言问题发给 Agent。Agent 会：检索知识库 → 用模型提取出结构化 ExperimentSpec → **缺什么参数就追问你**，绝不替你瞎编。这是"Agent 理解需求"环节，也是模型输出第一次进入系统的地方——所以后面必须有 Java 校验兜底。

### 4.2 发起对话

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"我想做极化码码维数识别实验：码长32和64，BSC误码率0到0.02，步长0.01，每点20个码字、重复10次，种子20"}'
```

预期：返回 Agent 回答（可能附带 Spec JSON 或追问）。**这一步会真实消耗一次 DashScope 额度。**

### 4.3 体验缺参追问（作用：验证 Agent 不编参数）

```bash
curl -s -X POST http://localhost:9900/api/wavepilot/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"做极化码码维数识别实验，错误率0到0.02"}'
```

预期：Agent 追问缺少的码长、样本数等参数，而不是直接给一个编造的 Spec。

> 降级提示：无模型时此接口返回明确错误；你仍然可以直接用第 5 步的结构化 JSON 走完整流程（跳过 Agent 理解环节）。

---

## 5. 第四步：Java 确定性校验

### 5.1 这一步在做什么

模型输出**不可信**（可能编参数、给非 2 的幂码长、越界误码率）。Java 校验器是硬门槛：不过校验，任务连创建都不允许。

### 5.2 校验合法 Spec

```bash
curl -s -X POST http://localhost:9900/api/experiments/spec/parse \
  -H "Content-Type: application/json" \
  -d '{"experimentType":"POLAR_CODE_K_IDENTIFICATION","codeLengths":[32,64],"errorRateStart":0.0,"errorRateEnd":0.02,"errorRateStep":0.01,"sampleCount":20,"monteCarloTimes":10,"randomSeed":20,"outputTypes":["ACCURACY_CSV","RUN_LOG"],"description":"demo"}'
```

预期：`{"valid":true,"errors":[],"warnings":[]}`

### 5.3 验证非法 Spec 被拦截（作用：看门槛真的会挡人）

把 `codeLengths` 改成 `[48]`（非 2 的幂）再发一次：

预期：`{"valid":false,"errors":["Every codeLength must be a power of two: 48"],...}`

> 这一步完全离线可用，是平台最核心的信任边界。

---

## 6. 第五步：创建异步实验任务

### 6.1 这一步在做什么

`POST /api/experiments` 会：再次 Java 校验 → 生成 Job + ExperimentPlan（模板、参数点总数、执行阶段）→ 把 Spec/Plan 登记为 Artifact → 提交给 Runner 异步执行。**立即返回，不阻塞**。

```bash
curl -s -X POST http://localhost:9900/api/experiments \
  -H "Content-Type: application/json" \
  -d '{"experimentType":"POLAR_CODE_K_IDENTIFICATION","codeLengths":[32,64],"errorRateStart":0.0,"errorRateEnd":0.02,"errorRateStep":0.01,"sampleCount":20,"monteCarloTimes":10,"randomSeed":20,"outputTypes":["ACCURACY_CSV","RUN_LOG"],"description":"demo"}'
```

预期：

```json
{"jobId":"JOB-XXXXXXXXXXXX","status":"QUEUED","plan":{"planId":"PLAN-...","experimentTemplateVersion":"polar-k-identification-simple-v1","totalRuns":6,"stages":["VALIDATE_SPEC","RUN_EXPERIMENT","VALIDATE_RESULT","REGISTER_ARTIFACTS"]},...}
```

**怎么看结果**：
- `totalRuns=6` = 2 个码长 × 3 个误码率点（这就是"参数点"总数）
- `experimentTemplateVersion` 是版本化 MATLAB 模板（真实执行只跑这个固定模板）
- 记下 `jobId`，后面全靠它

---

## 7. 第六步：观察执行过程

### 7.1 REST 轮询（这一步在做什么：查状态机的每个阶段）

```bash
curl -s http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/progress
```

预期（执行中 / 结束后）：

```json
{"jobId":"JOB-...","status":"RUNNING","progress":50,"stage":"RUN_EXPERIMENT","completedRuns":3,"totalRuns":6,"message":"...","timestamp":"..."}
```

阶段含义：`VALIDATE_SPEC`（校验配置）→ `RUN_EXPERIMENT`（MATLAB 跑参数网格）→ `VALIDATE_RESULT`（Java 校验 CSV/summary/MAT/PNG 签名与一致性）→ `SUCCEEDED`。

> 真实 MATLAB 下这一步会真的启动 MATLAB 进程跑 `polar-k-identification-simple-v1` 模板；Mock 下毫秒级完成。**状态永远以服务端为准，不要把 RUNNING 当成功。**

### 7.2 SSE 实时流（作用：不轮询，服务器主动推）

```bash
curl -N http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/stream
```

预期：每 200ms 推一条 `event: progress` 帧，终止状态后自动关闭。

> 前端工作台用的就是这条 SSE（断线自动重连）。脚本里想"等任务结束"，轮询 progress 直到 `status` 为 SUCCEEDED/FAILED/CANCELLED 即可。

### 7.3 取消任务（可选，作用：验证受控取消链路）

```bash
curl -s -X POST http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/cancel
```

预期：状态变为 `CANCELLED`（任务很大时才有机会赶上）。

---

## 8. 第七步：产物登记与校验

### 8.1 这一步在做什么

任务成功后，MATLAB 产出的 CSV/MAT/PNG/summary/日志都已登记进 ArtifactRegistry，带 **SHA-256、大小、相对路径**。API 只暴露相对路径，绝不暴露本机绝对路径。

```bash
curl -s http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/artifacts
```

预期：一组 Artifact 记录，包含 `artifactType`（ACCURACY_CSV / SUMMARY_JSON / RUN_LOG / MAT_RESULT…）、`sha256`、`size`、`mock=false`、`classification=SIMPLIFIED_BASELINE`、`algorithmValidated=false`、`validated=true`。

**怎么看结果**：`mock=false` 表示这次是真实 MATLAB 执行；`algorithmValidated=false` 表示这是简化基线，**没有**科研性能验证——这两个标记会一路带到报告和 Replay。

### 8.2 校验哈希没有被篡改

```bash
curl -s -X POST http://localhost:9900/api/artifacts/ART-XXXXXXXX/verify
```

预期：`{"valid":true,...}`。之后任何一步发现哈希对不上，校验都会失败——这就是报告"可追溯"的地基。

### 8.3 下载真实产物（作用：拿到数据本体，比如自己画图）

```bash
curl -s -o accuracy.csv http://localhost:9900/api/artifacts/ART-XXXXXXXX/download
head -3 accuracy.csv
```

预期：13 列契约 CSV（codeLength, trueK, errorRate, correctCount, … accuracy, meanEstimatedK, mae, bias, …）——这就是真实 MATLAB 跑出来的数值。

---

## 9. 第八步：生成带 Citation 的报告

### 9.1 这一步在做什么

Java 重新从 CSV 计算 min/max/mean 并与 summary 交叉核对 → 每个展示数值生成一条 Citation（指向 Artifact + SHA-256 + 行/字段）→ 模板渲染 Markdown 报告。**报告里的每个数字都能追溯到源文件**，模型没有机会改数。

```bash
curl -s -X POST http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/report
```

预期：返回报告文档（`generatedBy=TEMPLATE`，含全部结论与 Citation 列表）。

### 9.2 看报告数据与 Citation（作用：检查数字有没有引用支撑）

```bash
curl -s http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/report/data | head -c 600
curl -s http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/citations | head -c 800
```

预期：报告数据里每个 accuracy 点都带 `citationIds`；Citation 形如 `{"citationId":"CIT-...","artifactId":"ART-...","fieldName":"accuracy","rowReference":"6","value":0.5,"artifactSha256":"..."}`。

### 9.3 重新校验全部引用

```bash
curl -s -X POST http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/report/validate
```

预期：`{"valid":true,...}`。跨 Job 引用、篡改文件、未验证产物都会被这条校验拒绝。

> 只有真实 MATLAB（13 列契约）能走到这一步；Mock 任务的报告会明确报错"Unsupported accuracy.csv contract"。

---

## 10. 第九步：Replay 复现

### 10.1 这一步在做什么

用**完全相同的配置**（Spec、randomSeed、模板、算法版本）创建独立的新任务重跑一遍，然后结构化比较两次结果：严格轴（Spec/种子/运行器/模板/算法版本/行数/参数网格）＋ 数值容差（accuracy 最大/平均绝对差、MAE/bias 最大差，默认 1e-9），给出 REPRODUCIBLE 判定。**绝不覆盖原任务与原产物。**

```bash
curl -s -X POST http://localhost:9900/api/experiments/JOB-XXXXXXXXXXXX/replay \
  -H "Content-Type: application/json" -d '{"note":"第一次复现验证"}'
```

预期：返回 Replay 记录（`replayId`、`replayJobId`、`status=RUNNING`）。

### 10.2 等它跑完并看对比结果

```bash
curl -s http://localhost:9900/api/replays/REPLAY-XXXXXXXXXXXX/comparison
```

预期：`{"verdict":"REPRODUCIBLE","withinTolerance":true,"metrics":[{"metricName":"accuracy","maxAbsDifference":0.0,...}],...}`

### 10.3 看 Replay 清单（作用：理解"复现了什么"）

```bash
curl -s http://localhost:9900/api/replays/REPLAY-XXXXXXXXXXXX/manifest
```

预期：含 `canonicalExperimentSpec`、`randomSeed`、`matlabTemplateSha256`、`replayFingerprint` 等完整定义。

> 边界：**REPRODUCIBLE 只代表"同配置结果一致"，不代表算法已被科研验证**（`algorithmValidated=false` 全程保留）。MAT/PNG/日志不参与字节比较——它们受绘图环境/元数据影响，科研一致性以 CSV/summary 为准。

---

## 11. 第十步：Eval 评价

### 11.1 这一步在做什么

Eval 用 24 个固定 Case 检验**平台与 Agent 流程**（解析、缺参、非法参数拦截、工具安全、Job、Citation、Grounding、Replay），指标全部由真实执行结果计算，不是写死的百分比。

```bash
curl -s -X POST http://localhost:9900/api/evaluations/run \
  -H "Content-Type: application/json" -d '{"datasetName":"default","modelName":"stub-v1"}'
```

预期：24 个 Case 结果 + 11 项指标（`overallTaskCompletionRate` 应为 1.0，前提是环境配置正确）。

### 11.2 Baseline / Candidate 对比（作用：验证"改模型前必须逐 Case 看回归"）

先跑一个带缺陷的候选（stub-v2）：

```bash
curl -s -X POST http://localhost:9900/api/evaluations/run \
  -H "Content-Type: application/json" -d '{"datasetName":"default","modelName":"stub-v2"}'
```

再对比（记下两次返回的 `evaluationId`）：

```bash
curl -s -X POST http://localhost:9900/api/evaluations/compare \
  -H "Content-Type: application/json" \
  -d '{"baselineEvaluationId":"EVAL-BASE-...","candidateEvaluationId":"EVAL-CAND-..."}'
```

预期：`regressedCaseIds=["C-004","C-009"]`、`releaseAllowed=false`——退化 Case 被逐条点名，只看总分是看不到的。

> 真实模型 Eval（external-eval profile）需要 DashScope key，且只覆盖 Spec 类 Case；未运行时不得声称真实模型 Eval 通过。

---

## 12. 收尾：回到工作台核对

打开 http://localhost:9900/（Ctrl+F5），对照检查：

1. 边界徽章 = 绿色 `REAL MATLAB EXPERIMENT（真实 MATLAB 实验）| 简化基线（SIMPLIFIED_BASELINE）| 算法未验证（algorithmValidated=false）`
2. Job 列表里能看到**两个**任务：原任务 + Replay 新任务（标注"← 由 JOB-… 复现"）
3. 报告区渲染出带 Citation 的报告，点击"定位 Artifact"能高亮对应产物
4. Replay 区显示"可复现（REPRODUCIBLE）"
5. Eval 区显示两次运行 + 对比结果（退化 Case 列表）

---

## 13. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `401 InvalidApiKey` | 知识检索走了 DashScope Embedding，key 无效 | 设 `WAVEPILOT_EMBEDDING_OFFLINE=true`（配内存库）或提供真实 key |
| 报告报 `Unsupported accuracy.csv contract` | 任务由 Mock Runner 执行（3 列简版契约） | 用真实 MATLAB 模式重跑（第 1.2 节） |
| 任务一直 `QUEUED/RUNNING` | 真实 MATLAB 在跑大网格 | 等；或用 progress/SSE 观察 `completedRuns/totalRuns` |
| Replay 报 `Source job must be SUCCEEDED` | 源任务没成功 | 先查原任务 progress 与失败原因 |
| Replay 判定 `NOT_REPRODUCIBLE` | 严格轴不一致或数值超出容差 | 看 comparison 的 `message` 指明哪一项 |
| Eval 知识 Case 失败 | 知识库没被 Eval 语料 seed 或存储不可用 | 确认第 3 步上传成功；检查服务日志 |

## 14. 这个流程验证了什么（诚实版）

- **真实**：自然语言解析/对话（DashScope）、知识检索（真实库或内存降级）、MATLAB 执行（真实模式）、Java 校验/状态机/SSE/产物/报告/Citation/Replay/Eval 全部真实 Java 代码。
- **降级**：内存知识库 + 离线向量、Mock Runner 只验证软件闭环。
- **不声称**：REPRODUCIBLE ≠ 算法已验证；`algorithmValidated=false` 表示没有科研性能验证；本项目不是论文复现或创新算法成果。

## 15. 真实环境实测记录（2026-08-06）

以下结果在真实环境跑通：DashScope 真实模型 + 真实 Embedding + 本机 MATLAB R2023b + 内存知识库。

| 环节 | 实测结果 |
|---|---|
| Agent 自然语言 → 提交任务 | 真实模型提取全部参数，主动调用 submitExperiment 工具，提交 `JOB-F463D949-856` |
| 缺参追问 | 缺码长/样本数/次数时逐项追问，拒绝编造 |
| 真实 MATLAB 执行 | 6/6 参数点完成（约 20 秒），Java 校验通过 → SUCCEEDED |
| 产物登记 | 7 个 Artifact 全部 `mock=false`、`validated=true`：13 列 accuracy.csv、result.mat、accuracy-curve.png（42KB）、summary、日志 |
| 报告 + Citation | 9 条结论 + 59 条 Citation，`generatedBy=TEMPLATE`，重新校验 `valid: true`；报告标注"真实 MATLAB R2023b 执行，mock=false" |
| Replay 复现 | 真实 MATLAB 第二次执行，accuracy/MAE/bias 最大差全部为 0，判定 `REPRODUCIBLE` |
| Eval | 24/24 全部通过（含 6 个真实 MATLAB 任务 Case） |
| 前端 | 边界徽章为绿色"REAL MATLAB EXPERIMENT（真实 MATLAB 实验）\| 简化基线（SIMPLIFIED_BASELINE）\| 算法未验证（algorithmValidated=false）" |

期间发现并修复一个真实缺口：Eval 的 Job/Replay Case 等待上限原为硬编码 10 秒（为 Mock 设计），真实 MATLAB 下会超时；已改为可配置 `wavepilot.evaluation.job-wait-timeout-millis`（提交 `47e1087`）。
