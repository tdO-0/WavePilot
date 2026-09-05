# MySQL 与 RabbitMQ 实验任务后端

此扩展将任务状态放入 MySQL，将排队与执行通过 RabbitMQ 分开，便于演示一个 API 与多个 Worker。它是教学和项目展示级实现；默认配置仍为单进程、内存 Repository、本地直接编排，文件 Repository、Mock/Local MATLAB、Artifact、Replay、SSE 与 Scientific Execution Ledger 均保留。

## 配置与角色

| 配置 | 默认值 / 可选值 | 含义 |
|---|---|---|
| `wavepilot.job-repository` / `WAVEPILOT_JOB_REPOSITORY` | `in-memory`、`file`、`mysql` | MySQL 之外不会创建 DataSource 或执行迁移；兼容旧 `wavepilot.jobs.persistence` |
| `wavepilot.node-role` / `WAVEPILOT_NODE_ROLE` | `standalone`、`api`、`worker` | 后两者必须使用 MySQL，否则启动失败 |
| `WAVEPILOT_MYSQL_URL` | `jdbc:mysql://localhost:3306/wavepilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` | 数据库连接 |
| `WAVEPILOT_MYSQL_USER` / `WAVEPILOT_MYSQL_PASSWORD` | `wavepilot` / `wavepilot-dev-only` | 示例开发凭据 |
| `WAVEPILOT_RABBIT_HOST` / `WAVEPILOT_RABBIT_PORT` | `localhost` / `5672` | Broker 地址 |
| `WAVEPILOT_RABBIT_USER` / `WAVEPILOT_RABBIT_PASSWORD` | `wavepilot` / `wavepilot-dev-only` | 示例开发凭据 |
| `WAVEPILOT_ARTIFACT_ROOT` | `artifacts` | API 与 Worker 必须访问相同共享目录 |
| `WAVEPILOT_SHARED_ARTIFACT_METADATA` | `false` | 分布式演示设 `true`，用共享目录中原子写入的独立元数据文件同步 Artifact |

`standalone` 沿用原有线程池直接执行。`api` 接收请求、校验 Spec、保存任务并发布消息，不提交 Runner。`worker` 注册 Rabbit 消费者，通过原有 ExperimentService、ExperimentStateMachine、ExperimentRunner 执行实验；实验 Controller 不注册，Service 也拒绝直接创建任务。Worker 内部保留 HTTP 服务用于健康检查，Compose 不暴露其端口。

## 表结构与提交幂等

Flyway 启动时执行 `src/main/resources/db/migration/V1__experiment_job.sql`。`experiment_job` 包含自增主键 `id`、`job_id`、可空 `idempotency_key`、`spec_json`、`plan_json`、`status`、结构化 JSON `progress`、`external_job_id`、`failure_reason`、`version`、微秒时间戳 `created_at/updated_at`。另有 `generic_spec` 类型标记与 `source_job_id`，用于兼容声明式 Spec 和来源关系。

- `uk_experiment_job_id(job_id)`：业务任务 ID 唯一。
- `uk_experiment_idempotency(idempotency_key)`：允许多个 NULL，非 NULL 唯一；ASCII binary 排序规则区分大小写。
- `ix_experiment_status_created(status, created_at)`：辅助排队状态检查。
- `version`：普通状态/进度保存执行 `WHERE job_id=? AND version=?`，成功后加一，拒绝旧快照覆盖取消或其他较新状态。

`POST /api/experiments` 接受可选 `Idempotency-Key`，格式为 1–128 个可见 ASCII 字符。未提供则每次创建新任务；同一键始终复用第一次的 Spec 和任务，不按后续请求替换。调用方应为不同业务请求生成不同键。

Controller 和 Service 只访问 `ExperimentJobRepository`。MySQL 实现先 INSERT，依靠唯一索引解决跨进程竞争；捕获 `DuplicateKeyException` 后查询并返回已经提交的原任务。预查询只是快速路径，不承担并发保证。INSERT 不与冲突后的查询包在同一长事务里，避免读取早于获胜提交的事务快照。

## 创建与执行流程

1. API 校验 Spec，保存 CREATED，写入 Spec/Plan Artifact，依次保存 VALIDATED、QUEUED。
2. API 发布持久化 JSON 消息到 durable Direct Exchange `wavepilot.experiments`，Routing Key 为 `experiment.execute`，队列为 `wavepilot.experiments.execute`。
3. 消息只有 `messageId`、`jobId`、`executionId`、`createdAt`；`executionId=EXEC-<jobId>` 供关联日志使用，不修改既有 Scientific Ledger 格式。
4. Worker 根据 jobId 读取 MySQL，执行 `UPDATE ... SET status='RUNNING', version=version+1 WHERE job_id=? AND status='QUEUED' AND version=?`。只有 affected rows=1 的 Worker 能调用 Runner。
5. RUNNING、VALIDATING_RESULT、SUCCEEDED、FAILED、CANCELLED 消息作为重复消息 ACK。CREATED/VALIDATED 暂不具备执行条件，按临时错误有限重试。
6. 获胜 Worker 提交现有 Runner，保存 externalJobId、持续保存进度，校验和注册 Artifact，通过原状态机进入 SUCCEEDED。Runner/结果校验失败保存 FAILED 与原因。
7. GET 与 SSE 沿用现有接口。SSE 每 200ms 从 Repository 读取数据库最新状态；不使用 Redis 或进程间推送。API 取消任务后，Worker 轮询到 CANCELLED 时停止本进程 Runner；乐观锁防止旧进度覆盖取消。

## ACK、重试与死信

交付语义为**至少一次投递 + 幂等消费**，不提供 Exactly Once。

- 发布端启用 `mandatory`、publisher confirm 和 return 检查；等待确认最多 5 秒。无法确认则 HTTP 503，错误原因包含已保存 jobId。
- 消费端 MANUAL ACK、prefetch=1。成功完成和重复消息 `basicAck(tag, false)`。
- 抢占前的临时数据库错误：初始尝试后最多重试 3 次，间隔 100/200/400ms。重试在同一条未确认 delivery 内完成，不无限 requeue。
- 超限使用 `basicReject(tag, false)`，由队列 DLX `wavepilot.experiments.dlx` 和死信 Routing Key `experiment.dead` 路由到 `wavepilot.experiments.dead`。
- 非法消息、确定性实验失败、抢占后的不确定错误直接拒绝到 DLQ。已经提交 Runner 的实验不可安全重跑，不能为了“重试成功”重复外部副作用。最终状态写库失败也拒绝，不把内存终态当作持久化成功。
- ACK 自身失败不触发业务重跑。断连接后 Broker 可能重新投递，数据库状态负责抑制重复执行。

参考：[Spring AMQP 的 confirms/returns 说明](https://docs.spring.io/spring-amqp/reference/amqp/template.html)。

## 启动与验证

全链路容器演示（默认 Mock，无需 MATLAB/模型 Key）：

```powershell
docker compose -f docker-compose.backend.yml up -d --build
docker compose -f docker-compose.backend.yml ps
docker compose -f docker-compose.backend.yml up -d --scale wavepilot-worker=2
```

若本机已经完成测试和打包，可复用同一 Jar 构建镜像，省去容器内再次下载 Maven 依赖（默认仍支持从源码构建）：

```powershell
mvn -B package -DskipTests
$env:BACKEND_BUILD_TARGET = "backend-local"
docker compose -f docker-compose.backend.yml up -d --build
```

API：`http://localhost:9900`；Rabbit 管理台：`http://localhost:15672`，默认开发用户/密码为 `wavepilot` / `wavepilot-dev-only`。Compose 的 `BACKEND_MYSQL_PORT`、`BACKEND_RABBIT_PORT`、`BACKEND_API_PORT`、`BACKEND_RABBIT_MANAGEMENT_PORT` 可调整宿主机端口。所有暴露端口默认仅绑定本机。

同一个 Jar，分别在两个终端启动（先启动 MySQL/RabbitMQ，两个进程共享 Artifact 目录）：

```powershell
mvn -B package -DskipTests
$env:WAVEPILOT_JOB_REPOSITORY = "mysql"
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:WAVEPILOT_SHARED_ARTIFACT_METADATA = "true"
$env:WAVEPILOT_ARTIFACT_ROOT = "D:/wavepilot-shared/artifacts"
# API 终端
java -jar target/wavepilot-1.0.0-SNAPSHOT.jar --wavepilot.node-role=api --server.port=9900
# Worker 终端：先设置相同环境变量；本机多个 Worker 使用不同 HTTP 健康检查端口
java -jar target/wavepilot-1.0.0-SNAPSHOT.jar --wavepilot.node-role=worker --server.port=9901
```

接口验证：

```powershell
$body = Get-Content -Raw examples/reproducible-showcase/experiment-spec.json
$headers = @{ "Idempotency-Key" = "demo-001" }
$first = Invoke-RestMethod http://localhost:9900/api/experiments -Method Post -Headers $headers -ContentType application/json -Body $body
$second = Invoke-RestMethod http://localhost:9900/api/experiments -Method Post -Headers $headers -ContentType application/json -Body $body
$first.jobId -eq $second.jobId
Invoke-RestMethod "http://localhost:9900/api/experiments/$($first.jobId)"
curl.exe -N "http://localhost:9900/api/experiments/$($first.jobId)/stream"
Invoke-RestMethod "http://localhost:9900/api/experiments/$($first.jobId)/artifacts"
```

默认测试不连接数据库/Broker，也不要求 Docker：

```powershell
mvn -B clean test
```

真实基础设施 Profile（使用独立测试库/队列环境；测试会创建任务记录）：

```powershell
docker compose -f docker-compose.backend.yml up -d mysql rabbitmq-management
mvn -B -Pbackend-it verify
# 示例：3306 不可用时，将 MySQL 映射为 33306 后运行
mvn -B -Pbackend-it "-Dbackend.mysql.url=jdbc:mysql://localhost:33306/wavepilot?connectionTimeZone=UTC" verify
```

`BackendDistributedIT` 启动真实 Spring API/两个 Worker 上下文，验证 HTTP 连续/并发幂等、Runner 消费与重复投递、两独立 Repository 抢占同一行、GET/SSE/Artifact、跨进程 Replay、真实 Broker ACK 与重试后的 DLQ（含 `x-death`）。缺少基础设施时该 Profile 会失败，不静默跳过。重试异常由测试注入，数据库与 Broker 使用真实实例。

## 本次实际验证

- `mvn -B clean test`：当时 405 项全部通过；随后增加 3 项回归用例。
- 最终命令：`mvn -B -Pbackend-it "-Dbackend.mysql.url=jdbc:mysql://localhost:33306/wavepilot?connectionTimeZone=UTC" clean verify`。
- 最终 Surefire：408 项，0 failure、0 error、0 skipped（原有 388 项保留，新增 20 项）。Failsafe：6 项真实基础设施集成测试，全部通过，无跳过。
- 本机 Maven 使用 JDK 22，编译目标为 Java 17；API/Worker 容器实际使用 Java 17 运行。数据库为 MySQL 8.0.36，Broker 为 RabbitMQ 3.13-management。
- 使用 `BACKEND_BUILD_TARGET=backend-local` 将已测试 Jar 构建为同一镜像，启动一个 API、两个 Worker；连同 MySQL、RabbitMQ 共 5 个容器均为 healthy。从源码构建路径保留；本次容器验证使用已测试 Jar 路径。
- 容器 HTTP 冒烟：同键连续请求均返回 `JOB-A3369664-7FB`，最终 `SUCCEEDED`，外部任务 ID 为 `MOCK-a0274000-5d2`；API 可读取 5 个 validated Artifact，SSE 返回成功终态。
- 日志：工作区 `backend-default-tests.log`、`backend-final-verify.log`、`backend-docker-build.log`、`backend-compose-smoke.log`；这些运行日志不纳入 Git。测试报告位于 `target/surefire-reports` 和 `target/failsafe-reports`。

## 改动清单

- 构建/配置：`pom.xml`、`Main.java`、`application.yml`、`.env.example`、`Dockerfile`、`.dockerignore`；新增 `docker-compose.backend.yml`。
- 现有任务链：`ExperimentJob`、`ExperimentController`、`ExperimentExceptionHandler`、`ExperimentService`、`ExperimentJobRepository`、`InMemoryExperimentJobRepository`、`FileSystemExperimentJobRepository`。
- MySQL 新增：`experiment/repository/mysql/` 下 `MySqlJobConfiguration`、`ExperimentJobRow`、`ExperimentJobMapper`、`MySqlExperimentJobRepository`，以及 `db/migration/V1__experiment_job.sql`。
- Rabbit 新增：`experiment/messaging/` 下 `BackendRabbitConfiguration`、`ExperimentExecutionMessage`、`ExperimentMessagePublisher`、`ExperimentExecutionConsumer`。
- 跨进程适配：`ArtifactRegistry` 的可选共享元数据、`ReplayService` 的来源关系持久化；SSE 沿用现有 Controller 轮询逻辑。
- 测试：新增 `backend/BackendRepositoryTest`、`BackendConsumerTest`、`BackendServiceTest`、`BackendDistributedIT`；扩展 `ExperimentControllerContractTest`。
- 文档：更新 `README.md`，新增本说明。Java 主源码均位于 `src/main/java/org/example/wavepilot/`（入口 `Main.java` 位于其父包）；测试均位于 `src/test/java/org/example/wavepilot/`。

## 已知边界

- 没有 Outbox：数据库提交成功后进程可能崩溃、消息可能发送失败。QUEUED 任务需检查后手动补发；带相同幂等键再次提交可补发 QUEUED，幂等消费保证不会因此重跑。CREATED/VALIDATED 初始化中断需人工处理；无自动扫描补偿。
- 抢占成功后 Worker 崩溃，RUNNING/VALIDATING_RESULT 不会自动租约回收，重复消息按要求 ACK。需人工核查 Artifact、Runner 和数据库，不应直接重置 QUEUED。已有 Scientific Execution Ledger 保留原单实例边界，并未升级为分布式协调器。
- 最多 3 次重试针对一次活跃 delivery；进程在重试中崩溃后 Broker 重投会重新开始这个本地重试计数。没有持久化全局重试计数。
- 无法连接数据库时无法可靠保存最终失败原因；DLQ、Worker 日志和数据库中间态用于排查。拒绝到 DLQ 不等同于数据库一定是 FAILED。
- API/Worker 必须共享 Artifact 存储；当前元数据文件扫描适用于小规模演示。共享存储、本地文件 Ledger、模板库均不构成高可用设计；自定义模板需部署到每个 Worker，Local MATLAB 也需本机安装与相应共享存储配置。
- Replay 的执行仍走同一 Service，结果 Artifact 可跨进程读取；Replay/Eval/Agent 的普通运行态仍主要是单 API 进程范围，不支持多 API 高可用。
- RabbitMQ 采用单节点普通 durable 队列；无 quorum 集群或端到端事务。Broker 故障、死信转发故障、确认丢失和长实验超过 Broker consumer timeout 仍有操作边界。
- 示例开发密码不能用于公开部署。此改造不增加认证、跨节点调度、Redis、微服务治理或生产级高可用声明。
