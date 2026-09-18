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

Flyway 启动时执行 `src/main/resources/db/migration/` 下按版本顺序的全部迁移。

- `V1__experiment_job.sql`：`experiment_job` 包含自增主键 `id`、`job_id`、可空 `idempotency_key`、`spec_json`、`plan_json`、`status`、结构化 JSON `progress`、`external_job_id`、`failure_reason`、`version`、微秒时间戳 `created_at/updated_at`。另有 `generic_spec` 类型标记与 `source_job_id`，用于兼容声明式 Spec 和来源关系。
- `V2__outbox_event.sql`：事务 Outbox 事件表，见下节。
- `V3__experiment_job_worker_lease.sql`：`experiment_job` 新增 `worker_id`、`lease_until`、`heartbeat_at`、`attempt`、`fencing_token`，并增加 `ix_experiment_lease_status(status, lease_until)` 供租约扫描使用。全部可空/带默认值，旧库按版本顺序平滑升级。

`experiment_job` 约束与并发控制：

- `uk_experiment_job_id(job_id)`：业务任务 ID 唯一。
- `uk_experiment_idempotency(idempotency_key)`：允许多个 NULL，非 NULL 唯一；ASCII binary 排序规则区分大小写。
- `ix_experiment_status_created(status, created_at)`：辅助排队状态检查。
- `version`：普通状态/进度保存执行 `WHERE job_id=? AND version=? AND status=? AND worker_id <=> ? AND fencing_token=?`，成功后加一，拒绝旧快照覆盖取消、重排队或新 Worker 的较新状态。

`POST /api/experiments` 接受可选 `Idempotency-Key`，格式为 1–128 个可见 ASCII 字符。未提供则每次创建新任务；同一键始终复用第一次的 Spec 和任务，不按后续请求替换。调用方应为不同业务请求生成不同键。

Controller 和 Service 只访问 `ExperimentJobRepository`。MySQL 实现先 INSERT，依靠唯一索引解决跨进程竞争；捕获 `DuplicateKeyException` 后查询并返回已经提交的原任务。预查询只是快速路径，不承担并发保证。INSERT 不与冲突后的查询包在同一长事务里，避免读取早于获胜提交的事务快照。**只有“进入 QUEUED 与写入 Outbox 事件”被包进同一个本地事务**（`OutboxDispatchService`，基于 `DataSourceTransactionManager`），其余写路径维持自动提交。

## 事务 Outbox：创建与发布解耦

创建流程：

1. API 校验 Spec，保存 CREATED，写入 Spec/Plan Artifact，保存 VALIDATED。
2. **同一事务**内：`experiment_job` 更新为 QUEUED + 写入一条 `outbox_event`（`uk_outbox_event_job(job_id)` 唯一，同一任务重试只保留首条事件）。事务提交后 API 立即返回 202——请求线程不再等待 RabbitMQ 确认。
3. 仅 api 角色运行 `OutboxRelay`：定期（默认 1s）分批读取 `status='PENDING' AND next_retry_at<=now` 的事件，逐条执行条件更新认领（`next_retry_at` 预占一个 claim lease 窗口并 `version+1`，多 API 实例互斥；认领者崩溃后窗口过期即被其他 Relay 接替）。
4. Relay 使用与原先相同的发布语义（mandatory、Publisher Confirm、Return 检查、PERSISTENT），确认后 `status='SENT', sent_at=now`。失败则 `attempt_count+1` 并按有上限指数退避（默认 1s 基数、300s 封顶）设置 `next_retry_at`。
5. 服务重启后 PENDING 事件自动续传；同一幂等键重复 POST 只幂等追加事件（已存在则忽略）。

消息格式不变：`messageId`、`jobId`、`executionId=EXEC-<jobId>`、`createdAt`；`messageId` 在事件创建时固定，重试期间保持稳定。

## Worker 租约、fencing 与安全恢复

1. Worker 消费消息后读库，先绑定本进程身份与租约（`worker_id`、`lease_until=now+duration`、`heartbeat_at`），再执行原子抢占：`UPDATE ... SET status='RUNNING', worker_id=?, lease_until=?, heartbeat_at=?, attempt=attempt+1, fencing_token=fencing_token+1, version=version+1 WHERE job_id=? AND status='QUEUED' AND version=?`。只有 affected rows=1 的 Worker 能调用 Runner。
2. 执行期间每 200ms 的进度写回与独立心跳调度器（默认每 15s）持续刷新 `heartbeat_at`/`lease_until`；心跳不递增 `version`，不会与进度写回互相竞争。
3. **所有进度与终态写入**都必须匹配 `job_id + 当前状态 + worker_id + fencing_token + version`（`worker_id <=> ?` 空安全比较）。恢复运行后的旧 Worker、人工取消、租约重排队都会使其写入返回 0 行并抛乐观锁异常，无法覆盖新状态。
4. `LeaseRecoveryScanner`（api 与 worker 节点、MySQL 后端运行，条件更新保证并发安全）扫描 `status IN (RUNNING, VALIDATING_RESULT) AND lease_until < now - grace` 的任务，保守分类：
   - 无副作用 runner（默认 `mock`，由 `wavepilot.lease.safe-requeue-runner-types` 配置）→ 条件更新回 QUEUED（清空 worker 字段、`fencing_token+1`）并在**同一事务**补写 Outbox 事件，由 Relay 重新投递；
   - 其余 runner（如 `local-matlab`，可能已提交外部 MATLAB）→ `UNCERTAIN` 终态待人工核查，**绝不盲目重跑**；`RUNNING + external_job_id IS NULL` 也不能证明未跨过副作用边界（`runner.submit` 与 `external_job_id` 写回之间存在崩溃窗口），因此非白名单 runner 一律 UNCERTAIN；
   - `VALIDATING_RESULT` 且全部 Artifact 已 validated 并通过 SHA-256 复验 → 按既有证据重建 `SUCCEEDED`（覆盖 `markArtifactsValidated` 之后、`SUCCEEDED` 落库之前的崩溃窗口），不重复执行；
   - SUCCEEDED/FAILED/CANCELLED 不会被扫描选中，不会重跑。
5. 状态机新增终态 `UNCERTAIN`（RUNNING/VALIDATING_RESULT → UNCERTAIN），SSE 轮询与 Scientific Ledger 的 `awaitTerminal` 均按终态处理。失去所有权的旧 Worker 放弃写入：任务被重排队时停止本地 Runner（仅无副作用类型），被标 UNCERTAIN 时保留外部进程现场供人工核查。

## 创建与执行流程

1. API 校验 Spec，保存 CREATED，写入 Spec/Plan Artifact，保存 VALIDATED；QUEUED + Outbox 事件同事务提交（见上节）。
2. Relay 发布持久化 JSON 消息到 durable Direct Exchange `wavepilot.experiments`，Routing Key 为 `experiment.execute`，队列为 `wavepilot.experiments.execute`。
3. 消息只有 `messageId`、`jobId`、`executionId`、`createdAt`；`executionId=EXEC-<jobId>` 供关联日志使用，不修改既有 Scientific Ledger 格式。
4. Worker 按上节规则原子抢占并执行。
5. RUNNING、VALIDATING_RESULT、SUCCEEDED、FAILED、CANCELLED、UNCERTAIN 消息作为重复消息 ACK。CREATED/VALIDATED 暂不具备执行条件，按临时错误有限重试。
6. 获胜 Worker 提交现有 Runner，保存 externalJobId、持续保存进度与租约，校验和注册 Artifact，通过原状态机进入 SUCCEEDED。Runner/结果校验失败保存 FAILED 与原因。
7. GET 与 SSE 沿用现有接口。SSE 每 200ms 从 Repository 读取数据库最新状态；不使用 Redis 或进程间推送。API 取消任务后，Worker 轮询到 CANCELLED 时停止本进程 Runner；乐观锁 + fencing 防止旧进度覆盖取消。

## ACK、重试与死信

交付语义为**事务 Outbox + 至少一次投递 + 幂等消费**，不提供 Exactly Once。

- 发布端启用 `mandatory`、publisher confirm 和 return 检查；等待确认最多 5 秒。确认失败仅记录退避重试（Outbox 已持久化），不再产生请求线程内的 503；非 MySQL 的 api 回退路径保留原 503 语义。
- 消费端 MANUAL ACK、prefetch=1。成功完成和重复消息 `basicAck(tag, false)`。
- 抢占前的临时数据库错误：初始尝试后最多重试 3 次，间隔 100/200/400ms。重试在同一条未确认 delivery 内完成，不无限 requeue。
- 超限使用 `basicReject(tag, false)`，由队列 DLX `wavepilot.experiments.dlx` 和死信 Routing Key `experiment.dead` 路由到 `wavepilot.experiments.dead`。
- 非法消息、确定性实验失败、抢占后的不确定错误直接拒绝到 DLQ。已经提交 Runner 的实验不可安全重跑，不能为了“重试成功”重复外部副作用。最终状态写库失败也拒绝，不把内存终态当作持久化成功。
- ACK 自身失败不触发业务重跑。断连接后 Broker 可能重新投递，数据库状态负责抑制重复执行。

参考：[Spring AMQP 的 confirms/returns 说明](https://docs.spring.io/spring-amqp/reference/amqp/template.html)。

## 可观测性

新增 `micrometer-registry-prometheus`，暴露 `/actuator/prometheus`；所有指标以 `wavepilot_` 为前缀，不使用 jobId/userId 等高基数标签（状态等低基数标签除外）。

| 指标族 | 含义 |
|---|---|
| `wavepilot_jobs_created_total` | 创建任务总数 |
| `wavepilot_jobs_completed_total{status=...}` | 成功 / 失败 / 取消 / UNCERTAIN 任务数 |
| `wavepilot_jobs_queue_time_seconds` | 任务排队时间（创建 → Worker 抢占） |
| `wavepilot_jobs_execution_time_seconds` | 任务执行耗时（抢占 → 终态） |
| `wavepilot_jobs_current{status=...}` | 当前各状态任务数量（转换增量 + MySQL 周期全量校准） |
| `wavepilot_outbox_pending` | Outbox 待发送事件数 |
| `wavepilot_outbox_published_total` / `wavepilot_outbox_publish_failures_total` / `wavepilot_outbox_retries_total` | Outbox 发送成功 / 失败 / 重试次数 |
| `wavepilot_lease_expired_total{outcome=requeued\|uncertain\|rebuilt}` | Worker 租约过期次数（按恢复结果分类） |
| `wavepilot_lease_claims_total{result=won\|lost}` | Worker 抢占成功 / 失败次数 |
| `wavepilot_rabbitmq_consumed_total` / `wavepilot_rabbitmq_duplicates_total` / `wavepilot_rabbitmq_dlq_total` | RabbitMQ 消费成功 / 重复消息 / 进入 DLQ 数量 |

Compose 提供可选 `monitoring` profile（Prometheus + Grafana），不影响默认测试与 CI：

```powershell
docker compose -f docker-compose.backend.yml --profile monitoring up -d
# Prometheus http://localhost:9090，Grafana http://localhost:3000（匿名管理员）
```

关键配置：`wavepilot.outbox.*`（relay 间隔、批量、认领窗口、退避）、`wavepilot.lease.*`（租约时长、心跳/扫描间隔、扫描宽限、可安全重排队的 runner 白名单），详见 `application.yml`。

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

`BackendDistributedIT` 启动真实 Spring API/两个 Worker 上下文，验证 HTTP 连续/并发幂等、Runner 消费与重复投递、两独立 Repository 抢占同一行、GET/SSE/Artifact、跨进程 Replay、真实 Broker ACK 与重试后的 DLQ（含 `x-death`）。`BackendOutboxIT` 验证任务与 Outbox 事件同事务提交、真实事务回滚后两者都不存在、Relay 停用后重启续传、双 API 实例并发 Relay、SENT 事件不重复发送、重复投递不重复执行、`/actuator/prometheus` 输出。`BackendLeaseRecoveryIT` 验证过期租约重排队并由新 Worker 执行（attempt/fencing 递增）、跨过副作用边界与无法确认结果的任务标记 UNCERTAIN 且不重跑、已验证 Artifact 重建 SUCCEEDED、取消状态不被旧 Worker 覆盖。缺少基础设施时该 Profile 会失败，不静默跳过。重试异常由测试注入，数据库与 Broker 使用真实实例。

## 本次实际验证

- 最终命令：`mvn -B clean test`；`mvn -B -Pbackend-it "-Dbackend.mysql.url=jdbc:mysql://localhost:33306/wavepilot?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true" verify`（本机 3306 被占用，MySQL 容器映射到 33306）。
- 最终 Surefire：441 项，0 failure、0 error、0 skipped（原有 408 项保留，新增 33 项）。Failsafe：16 项真实基础设施集成测试（`BackendDistributedIT` 6 项、`BackendOutboxIT` 6 项、`BackendLeaseRecoveryIT` 4 项），全部通过，无跳过。
- 本机 Maven 使用 JDK 22，编译目标为 Java 17。数据库为 MySQL 8.0.36（Compose 容器），Broker 为 RabbitMQ 3.13-management。
- 测试期间发现并修复两个真实缺陷：① Boot 3.2 中带无参构造器的 `@ConfigurationProperties` record 会使构造绑定整体失效（命令行/环境变量覆盖被静默忽略），已删除无参构造器并新增 `LeasePropertiesBindingTest` 回归守护；② `@Scheduled(fixedDelayString)` 只接受毫秒数值字符串，调度间隔属性统一改为 `*-ms`。
- 日志：工作区 `backend-default-tests.log`、`backend-final-verify.log`、`backend-docker-build.log`、`backend-compose-smoke.log` 为上一阶段运行日志，不纳入 Git。测试报告位于 `target/surefire-reports` 和 `target/failsafe-reports`。

## 改动清单

- 构建/配置：`pom.xml`、`Main.java`、`application.yml`、`.env.example`、`Dockerfile`、`.dockerignore`；新增 `docker-compose.backend.yml`。
- 现有任务链：`ExperimentJob`、`ExperimentController`、`ExperimentExceptionHandler`、`ExperimentService`、`ExperimentJobRepository`、`InMemoryExperimentJobRepository`、`FileSystemExperimentJobRepository`。
- MySQL 新增：`experiment/repository/mysql/` 下 `MySqlJobConfiguration`、`ExperimentJobRow`、`ExperimentJobMapper`、`MySqlExperimentJobRepository`，以及 `db/migration/V1__experiment_job.sql`。
- Rabbit 新增：`experiment/messaging/` 下 `BackendRabbitConfiguration`、`ExperimentExecutionMessage`、`ExperimentMessagePublisher`、`ExperimentExecutionConsumer`。
- 跨进程适配：`ArtifactRegistry` 的可选共享元数据、`ReplayService` 的来源关系持久化；SSE 沿用现有 Controller 轮询逻辑。
- 测试：新增 `backend/BackendRepositoryTest`、`BackendConsumerTest`、`BackendServiceTest`、`BackendDistributedIT`；扩展 `ExperimentControllerContractTest`。
- 文档：更新 `README.md`，新增本说明。Java 主源码均位于 `src/main/java/org/example/wavepilot/`（入口 `Main.java` 位于其父包）；测试均位于 `src/test/java/org/example/wavepilot/`。

### 本次更新（事务 Outbox / 租约恢复 / 可观测性）

- 事务 Outbox：`experiment/outbox/` 下 `OutboxPayload`、`OutboxEventStatus`、`OutboxEventStore`、`OutboxDispatchService`、`OutboxRelay`、`OutboxProperties`、`OutboxMetrics` 与 `outbox/mysql/` 下 `OutboxEventRow`、`OutboxEventMapper`、`MySqlOutboxEventStore`；迁移 `V2__outbox_event.sql`。
- Worker 租约：`experiment/lease/` 下 `LeaseProperties`、`LeaseConfiguration`、`WorkerIdentity`、`WorkerHeartbeat`、`LeaseRecoveryScanner`、`LeaseMetrics`；迁移 `V3__experiment_job_worker_lease.sql`；`ExperimentStatus` 新增 `UNCERTAIN`，`ExperimentJob`/`ExperimentJobRow`/Mapper 增加租约字段与 fencing 谓词，`ExperimentJobRepository` 增加 `save(job, expectedStatus)`、`tryClaim(job, workerId)`、`updateHeartbeat`、`requeue`、`findExpiredLeases`。
- 可观测性：`experiment/metrics/` 下 `JobExecutionMetrics`、`MySqlJobStatusSync`；`micrometer-registry-prometheus` 依赖、`management` 配置、Compose `monitoring` profile 与 `monitoring/` 目录下的 Prometheus/Grafana 最小配置。
- 测试：新增 `OutboxRelayTest`、`MySqlOutboxEventStoreTest`、`OutboxDispatchServiceTest`、`LeaseRecoveryScannerTest`、`WorkerHeartbeatTest`、`WavePilotMetricsTest`、`BackendOutboxIT`、`BackendLeaseRecoveryIT`；扩展 `BackendRepositoryTest` 与 `BackendDistributedIT`。

## 已知边界

- 投递语义为**至少一次**：Outbox 消除了“数据库已提交但消息未发送”的丢失窗口，但 Relay 在“confirm 之后、标记 SENT 之前”崩溃会产生重复投递，由数据库幂等抢占抑制。不声明 Exactly Once。
- Worker 恢复是保守的：只有无外部副作用的 runner（默认 `mock`）才在租约过期后自动重排队；`local-matlab` 等真实执行器一律 `UNCERTAIN` 人工核查——`external_job_id` 的写回晚于 `runner.submit`，无法从数据库证明外部副作用尚未发生。`standalone` 角色不参与租约扫描/心跳（没有消费者接住重排队的任务），其 mysql 模式不提供崩溃自动恢复。
- Outbox Relay 只在 api 角色运行；若所有 api 实例停止，重排队事件保持 PENDING 直至 api 恢复。`outbox_event` 无最大尝试次数上限，Broker 长期不可用时以封顶退避持续重试（这是有意设计，配合告警使用）。
- 最多 3 次重试针对一次活跃 delivery；进程在重试中崩溃后 Broker 重投会重新开始这个本地重试计数。没有持久化全局重试计数。
- 无法连接数据库时无法可靠保存最终失败原因；DLQ、Worker 日志和数据库中间态用于排查。拒绝到 DLQ 不等同于数据库一定是 FAILED。
- API/Worker 必须共享 Artifact 存储；当前元数据文件扫描适用于小规模演示。共享存储、本地文件 Ledger、模板库均不构成高可用设计；自定义模板需部署到每个 Worker，Local MATLAB 也需本机安装与相应共享存储配置。
- Replay 的执行仍走同一 Service，结果 Artifact 可跨进程读取；Replay/Eval/Agent 的普通运行态仍主要是单 API 进程范围，不支持多 API 高可用。
- RabbitMQ 采用单节点普通 durable 队列；无 quorum 集群或端到端事务。Broker 故障、死信转发故障、确认丢失和长实验超过 Broker consumer timeout 仍有操作边界。
- 科研智能体执行账本仍为单实例文件实现，未升级为分布式协调器；实验任务层的“重建”依据已验证 Artifact（SHA-256 复验），不涉及 Scientific Ledger。
- 示例开发密码不能用于公开部署。此改造不增加认证、跨节点调度、Redis、微服务治理或生产级高可用声明。
