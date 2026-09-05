<h1 align="center">🌊 WavePilot</h1>
<p align="center"><strong>通信仿真实验智能体平台</strong></p>
<p align="center">从自然语言目标出发，让实验可执行、结果可核验、过程可回放。</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-E76F00?style=flat-square&amp;logo=openjdk&amp;logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?style=flat-square&amp;logo=springboot&amp;logoColor=white" alt="Spring Boot 3.2">
  <img src="https://img.shields.io/badge/MySQL-可选持久化-4479A1?style=flat-square&amp;logo=mysql&amp;logoColor=white" alt="MySQL 可选持久化">
  <img src="https://img.shields.io/badge/RabbitMQ-异步任务-FF6600?style=flat-square&amp;logo=rabbitmq&amp;logoColor=white" alt="RabbitMQ 异步任务">
</p>
<p align="center">
  <a href="#version"><img src="https://img.shields.io/badge/开发版本-1.0.0--SNAPSHOT-7C3AED?style=flat-square" alt="开发版本 1.0.0-SNAPSHOT"></a>
  <a href="https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml"><img src="https://github.com/tdO-0/WavePilot/actions/workflows/java17-ci.yml/badge.svg" alt="Java 17 持续集成状态"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/许可证-Apache--2.0-475569?style=flat-square" alt="Apache 2.0 许可证"></a>
</p>
<p align="center">
  <a href="#quick-start">快速开始</a> ·
  <a href="#demo">操作演示</a> ·
  <a href="#architecture">核心架构</a> ·
  <a href="#backend">异步任务后端</a> ·
  <a href="#validation">测试验证</a> ·
  <a href="#docs">项目文档</a>
</p>

WavePilot 面向通信仿真实验，将自然语言目标转换为经过 Java 校验的实验参数（`ExperimentSpec`），串联知识检索、异步执行、结果文件校验、基于证据的报告、实验回放与离线评测。持久化执行账本（Execution Ledger）用于识别并复用已完成的实验，减少重启后的重复执行。

默认使用离线模拟执行器（Mock Runner）。模拟指标只用于验证软件链路，不代表 MATLAB 或通信算法的科研结果。

<a id="version"></a>

## ✨ 版本说明

### 1.0.0-SNAPSHOT · MySQL 与 RabbitMQ 后端扩展（2026-09-06）

本次为现有开发版本的增量更新，Maven 版本号保持 `1.0.0-SNAPSHOT`。

- 新增可选 MySQL 持久化：基于 MyBatis-Plus 的仓储实现、Flyway 数据库迁移、唯一索引提交幂等与乐观锁状态更新。
- 新增 RabbitMQ 异步执行：同一 Jar 支持 `standalone`、`api`、`worker`，通过数据库条件更新避免多个 Worker 重复执行；支持手动 ACK、最多 3 次临时异常重试及死信队列。
- 保留内存/文件仓储、本地执行、模拟与本机 MATLAB 执行器、科研智能体执行账本和原有 SSE；补充跨进程结果文件元数据读取与实验回放来源关系持久化。
- 新增 [后端 Docker Compose](docker-compose.backend.yml) 和 [启动、验证及边界说明](docs/BACKEND_DISTRIBUTED_TASK.md)。已验证一个 API 与两个 Worker 使用同一镜像运行。
- 验证结果：408 项默认测试及 6 项真实 MySQL/RabbitMQ 集成测试全部通过，0 失败、0 错误、0 跳过。默认测试不要求外部数据库、消息队列或 Docker。

交付语义为“至少一次投递 + 幂等消费”。尚无 Outbox 和 Worker 崩溃后的自动接管，定位为教学与项目展示级实现。

<p align="center">
  <img src="docs/assets/wavepilot-workbench.png" alt="WavePilot 结果与证据工作台" width="100%">
</p>

<a id="architecture"></a>

## 🧭 核心架构

```text
实验目标
  → 结构化计划：仅选择已注册能力
  → 混合检索：向量检索 + Lucene BM25 + RRF 融合 + 受控重排
  → Java 校验：实验参数、执行预算、参数调整边界
  → 幂等执行：提交实验并记录执行账本
  → 结果观察：读取已校验的实验文件
  → 确定性验证：依据实际结果判断是否达成目标
  → 有界重规划，或进入终态
  → 实验回放、检索评测与智能体回归评测
```

模型只能提出结构化计划、候选排序或下一组实验参数；Java 负责能力白名单、数据结构、参数边界、执行预算、结果文件哈希和最终执行授权。模型不能生成或执行任意 Java、MATLAB、Shell 代码或自定义可执行程序。

<a id="demo"></a>

## 🎬 30 秒操作演示

<p align="center">
  <img src="docs/assets/wavepilot-demo.gif" alt="WavePilot 30 秒操作演示" width="100%">
</p>

演示覆盖目标输入、参数校验、模拟实验、结果校验、带引用的报告、实验回放与离线评测。可复现输入、预期 CSV 和校验脚本位于 [演示示例目录](examples/reproducible-showcase)。

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn spring-boot:run
```

打开 <http://localhost:9900>，或另开 PowerShell 执行：

```powershell
.\examples\reproducible-showcase\run.ps1
```

## 🔄 科研智能体执行闭环

`目标 → 计划 → 检索 → 执行 → 观察 → 验证 → 重规划 / 完成`

- `ScientificPlanner` 默认输出确定性安全计划；可选模型规划器只能选择已注册的 `ScientificCapability`。
- `BoundedScientificReplanner` 默认执行确定性参数调整；模型提出的语义重规划也必须通过参数边界、最大单步变化、实验参数校验和剩余预算检查。
- `RunBudget` 限制迭代次数、实验次数、模型调用次数、词元消耗、重试次数与总运行时间。
- 智能体在关键阶段原子保存检查点；执行账本持久化执行 ID、参数指纹、任务 ID、状态与结果文件引用。

## 🔎 混合检索与知识增强

- 向量检索：使用 Milvus，或用于离线演示的内存向量库。
- 关键词检索：使用进程内 Apache Lucene BM25；领域分词器支持中文双字组合、英文、数字、通信缩写、`Eb/N0`、驼峰和下划线命名。
- 排名融合：按稳定的 `chunkId` 执行倒数排名融合（RRF），不直接线性混合向量与 BM25 的原始分数。
- 查询路由：用户显式指定的元数据条件作为严格过滤；系统推断的文档类型仅影响权重，保留其他类型候选。
- 受控重排：默认按词项重合度确定性重排；可选 DashScope 模型只能返回已有片段 ID 的完整排列，非法输出自动回退。
- 引用溯源：重排前后完整保留来源、章节、元数据及 `KB[documentId/chunkId]` 引用。

## 📊 实验回放、评测与证据校验

WavePilot 的结论只来自通过结构校验和 SHA-256 校验的结果文件（Artifact）。实验回放使用相同参数与随机种子创建独立任务，并按声明容差比较数值结果。

检索评测数据集 `wavepilot-bilingual-retrieval-v2` 包含 80 个查询，理论、参数、故障诊断、实验指导各 20 个。它覆盖中英文、混合语言、缩写、同义改写、低字面重合、多个相关片段、跨章节检索、难负例和显式元数据过滤。

下表来自 `RetrievalEvaluationReportTest` 的实际离线运行。离线向量检索使用确定性模拟嵌入，不是通用语义模型的基准测试；模型重排策略在未配置服务提供方时使用确定性回退。

| 检索策略 | R@1 | R@3 | R@5 | P@3 | P@5 | MRR | nDCG@5 | 引用有效率 | 难负例拒绝率 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 仅向量检索 | 0.262500 | 0.406250 | 0.531250 | 0.150000 | 0.117500 | 0.378333 | 0.402777 | 0.562500 | 0.787500 |
| 仅 BM25 | 0.756250 | 0.885417 | 0.904167 | 0.333333 | 0.205000 | 0.856667 | 0.857722 | 0.937500 | 0.700000 |
| RRF 混合检索 | 0.537500 | 0.756250 | 0.879167 | 0.279167 | 0.197500 | 0.700000 | 0.731431 | 0.912500 | 0.625000 |
| 混合检索＋确定性重排 | 0.743750 | 0.879167 | 0.904167 | 0.325000 | 0.205000 | 0.850000 | 0.853227 | 0.937500 | 0.662500 |
| 混合检索＋模型策略（离线回退） | 0.743750 | 0.879167 | 0.904167 | 0.325000 | 0.205000 | 0.850000 | 0.853227 | 0.937500 | 0.662500 |

其中 R@k 为前 k 条结果的召回率，P@k 为精确率，MRR 和 nDCG 衡量排序质量。

相对离线向量检索，RRF 混合检索的 R@5 增加 `0.347917`；加入确定性重排后，混合检索的 R@1 增加 `0.206250`。BM25 在这组领域小语料上仍略优于混合重排，因此项目不声称混合检索或模型重排全面优于 BM25。默认持续集成不调用真实模型。

<a id="validation"></a>

## ✅ 工程测试与验证

```powershell
mvn -B clean test
mvn -B "-Dtest=RetrievalEvaluationReportTest" test
mvn -B "-Dtest=AgentRegressionEvaluationTest,ScientificAgentLoopTest,ExecutionLedgerRecoveryTest" test
```

| 验证项目 | 已验证结果 |
|---|---:|
| Maven 默认测试 | 408 项通过，0 失败、0 错误、0 跳过 |
| MySQL / RabbitMQ 集成测试（`backend-it`） | 6 项通过；真实基础设施，离线模拟执行器 |
| 检索查询 | 80 条，每类 20 条 |
| 检索策略数 / 评测结果数 | 5 / 400 |
| 智能体回归评测 | 基线与候选方案均通过 17/17 个维度 |
| 候选方案检索召回率 / 引用有效率 | 0.904167 / 0.937500 |
| 测试中的重复执行率 | 0 |
| 已完成任务的账本恢复 | 复用已验证结果文件，不新建任务 |
| 不确定提交的恢复 | 标记为 `UNCERTAIN`，不重复提交任务 |

测试验证的是编排、安全边界、恢复、检索和证据链。模拟实验的 `averageAccuracy`、测试延迟与离线嵌入指标不能用作科研性能结论。

## 🧠 可选真实模型接入

默认测试完全离线。要评测真实 DashScope 列表重排模型、规划器和重规划器，请先设置有效凭据并启动应用：

```powershell
$env:DASHSCOPE_API_KEY = "<your-key>"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:WAVEPILOT_MODEL_RERANKER_ENABLED = "true"
$env:WAVEPILOT_RERANKER = "model"
$env:WAVEPILOT_SCIENTIFIC_PLANNER_MODE = "model"
$env:WAVEPILOT_SCIENTIFIC_REPLANNER_MODE = "model"
mvn spring-boot:run
```

随后调用 `POST /api/retrieval-evaluations/run`，从 JSON/Markdown 结果文件核对实际使用的重排器（`rerankerUsed`）、质量指标和延迟。真实模型结果只有在实际运行后才能对外表述。

<a id="quick-start"></a>

## 🚀 快速开始

需要 JDK 17+ 与 Maven 3.9+。以下离线配置不需要模型密钥、Milvus 或 MATLAB。

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn -B clean test
mvn spring-boot:run
```

Docker 全栈（WavePilot + Milvus，默认使用模拟执行器）：

```powershell
docker compose up -d --build
```

真实本机 MATLAB 模式使用仓库内固定模板和参数文件，不允许模型拼接任意命令：

```powershell
$env:WAVEPILOT_RUNNER_TYPE = "local-matlab"
$env:MATLAB_EXECUTABLE = "C:\Program Files\MATLAB\R2025b\bin\matlab.exe"
mvn spring-boot:run
```

<a id="backend"></a>

## 📨 可选 MySQL 与 RabbitMQ 后端

默认使用本地执行与内存仓储；文件模式设 `WAVEPILOT_JOB_REPOSITORY=file`。可选后端使用 MySQL、MyBatis-Plus、Flyway 与 Spring AMQP，支持数据库幂等提交、条件更新抢占、手动确认、有限重试和死信。现有 GET/SSE 接口从仓储读取最新进度。

| 运行角色 | 职责 | 执行方式 |
|---|---|---|
| `standalone`（单机） | 接收请求并执行实验 | 原有本地线程池 |
| `api`（接口） | 保存任务、发布消息、提供查询与 SSE | 不直接执行实验 |
| `worker`（执行） | 消费消息、抢占任务、调用执行器 | 可启动多个进程，数据库协调抢占 |

```powershell
docker compose -f docker-compose.backend.yml up -d --build
docker compose -f docker-compose.backend.yml up -d --scale wavepilot-worker=2
# 默认单元测试无需 MySQL、RabbitMQ、Docker
mvn -B clean test
# 真实集成验证：先单独启动 Compose 的 mysql、rabbitmq-management 服务
mvn -B -Pbackend-it verify
```

同一个 Jar 可分别启动接口进程和执行进程；两者需要相同的 MySQL/RabbitMQ 配置，并共享结果文件目录：

```powershell
$env:WAVEPILOT_JOB_REPOSITORY = "mysql"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:WAVEPILOT_SHARED_ARTIFACT_METADATA = "true"
java -jar target/wavepilot-1.0.0-SNAPSHOT.jar --wavepilot.node-role=api --server.port=9900
# 另一终端，使用相同环境配置
java -jar target/wavepilot-1.0.0-SNAPSHOT.jar --wavepilot.node-role=worker --server.port=9901
```

接口验证：

```powershell
$body = Get-Content -Raw examples/reproducible-showcase/experiment-spec.json
$headers = @{ "Idempotency-Key" = "demo-001" }
$a = Invoke-RestMethod http://localhost:9900/api/experiments -Method Post -Headers $headers -ContentType application/json -Body $body
$b = Invoke-RestMethod http://localhost:9900/api/experiments -Method Post -Headers $headers -ContentType application/json -Body $body
$a.jobId -eq $b.jobId
Invoke-RestMethod "http://localhost:9900/api/experiments/$($a.jobId)"
curl.exe -N "http://localhost:9900/api/experiments/$($a.jobId)/stream"
```

连接变量为 `WAVEPILOT_MYSQL_URL/USER/PASSWORD`、`WAVEPILOT_RABBIT_HOST/PORT/USER/PASSWORD`；共享文件根目录为 `WAVEPILOT_ARTIFACT_ROOT`。Compose 默认使用模拟执行器与开发示例密码，接口端口为 9900，RabbitMQ 管理台端口为 15672。完整环境变量、表结构和重试语义见 [后端任务说明](docs/BACKEND_DISTRIBUTED_TASK.md)。

这是教学和项目展示级的“至少一次投递 + 幂等消费”。没有 Outbox，数据库提交与消息发布间仍有失败窗口；Worker 抢占后崩溃需要人工核查，不声明 Exactly Once 或生产级高可用。

## 🧩 技术栈

- Java 17、Spring Boot 3.2、Maven、JUnit 5
- Spring AI Alibaba / DashScope（可选）
- Milvus Java SDK（可选）与 Apache Lucene BM25
- MySQL、MyBatis-Plus、Flyway 与 Spring AMQP / RabbitMQ（可选后端）
- 文件型原子检查点与执行账本（仓储接口可替换）
- 原生 HTML/CSS/JavaScript 工作台、SSE
- GitHub Actions 持续集成（Java 17）

<a id="docs"></a>

## 📚 项目文档

- [MySQL 与 RabbitMQ 异步任务后端](docs/BACKEND_DISTRIBUTED_TASK.md)
- [整体架构](src/docs/ARCHITECTURE.md)
- [混合检索设计](src/docs/HYBRID_RETRIEVAL.md)
- [科研智能体执行闭环](src/docs/SCIENTIFIC_AGENT_LOOP.md)
- [持久化执行与恢复](src/docs/DURABLE_EXECUTION.md)
- [评测体系设计](src/docs/EVAL_DESIGN.md)
- [结果文件与引用溯源](src/docs/ARTIFACT_PROVENANCE.md)
- [实验回放设计](src/docs/REPLAY_DESIGN.md)
- [安全边界](src/docs/SECURITY_BOUNDARIES.md)
- [接口调用示例](src/docs/API_EXAMPLES.http)
- [实验类型扩展指南](src/docs/EXPERIMENT_TYPE_EXTENSION_GUIDE.md)

## 🛡️ 当前边界

- 默认离线测试与持续集成不调用真实模型、Milvus 或 MATLAB。
- 科研智能体执行账本仍为单实例文件实现；MySQL 持久化覆盖实验任务，不提供分布式租约或多实例主节点选举。
- Lucene 索引可在进程内重建；重启后需从权威知识源重新导入。
- 实验回放与评测的运行态仍主要在内存中，不提供多接口实例之间的高可用协调。
- 已完成账本记录只有在结果路径、文件大小、SHA-256 和验证标记全部检查通过后才复用；无法确认的副作用保持 `UNCERTAIN`，不会被当成成功。
- 模拟执行与离线嵌入结果不能证明通信算法精度、收敛性、吞吐或模型能力。

## 📄 开源许可

[Apache License 2.0](LICENSE)
