# Phase 3.5 外部依赖验证

验证日期：2026-08-06

## 验收边界

本阶段记录 Java 17、Milvus、DashScope Chat/Embedding 与受控 Agent 的外部依赖验证方法。

## 测试分层

| 层次 | Maven 命令 | 是否依赖外部服务 | 默认执行 |
|---|---|---|---|
| 单元与契约测试 | `mvn -B clean test` | 否，使用 Fake/Stub/内存 Repository | 是 |
| Milvus smoke | `mvn -B -Pmilvus-smoke verify` | 是，Milvus | 否 |
| DashScope smoke | `mvn -B -Pdashscope-smoke verify` | 是，DashScope API Key 与网络 | 否 |
| MATLAB 集成测试 | Phase 4 再定义 | 是，MATLAB | 本阶段不存在 |

`MilvusSmokeIT` 和 `DashScopeSmokeIT` 会被默认构建编译，但只有对应 Failsafe profile 才执行，因此普通 `mvn test` 不访问外部网络。

## Java 17 实际验证

项目编译目标为 Java 17。Windows 主机没有本地 JDK 17，主机 Maven 使用 Oracle JDK 22.0.2；另使用 `maven:3.9.9-eclipse-temurin-17` 容器实际执行了规定命令：

```powershell
docker run --rm --name wavepilot-java17-verify `
  -v "${PWD}:/workspace" `
  -v "D:\javatool\apache-maven-3.9.9\repository:/root/.m2/repository" `
  -w /workspace `
  maven:3.9.9-eclipse-temurin-17 `
  mvn -B clean test
```

实际结果：Temurin 17.0.15，`javac [debug release 17]`，49 项测试全部通过，0 failure、0 error、0 skipped，`BUILD SUCCESS`。首次运行因核验主机 Maven 缓存的 repository metadata 耗时 8 分 03 秒；密钥日志修复后，当前最终源码的干净复跑耗时 2 分 00 秒，同样通过。

`.github/workflows/java17-ci.yml` 也配置了 Temurin 17 和同一条 Maven 命令，但项目没有 GitHub remote，且没有 push，所以 GitHub Actions 没有真实运行。本地容器通过不能表述为 GitHub Actions 已通过。

## Git 安全基线

项目及上级目录原先都不是 Git 仓库。本轮在项目根目录执行了本地 `git init`，分支为 `main`，创建初始提交 `d8b3559`；没有绑定远端，也没有 push。

`.gitignore` 排除了 `target`、运行 Artifact、smoke 输出、日志、上传目录、`.env*` 以及本地/secret application 配置。配置文件和测试代码只引用环境变量名，不保存 API Key。

## 真实 Milvus smoke

运行方式：Docker Compose 启动 `etcd`、`minio` 与 standalone Milvus；Milvus 镜像为 `milvusdb/milvus:v2.5.10`，端口为 `19530`。

```powershell
docker compose -f vector-database.yml up -d etcd minio standalone
mvn -B -Pmilvus-smoke "-Dwavepilot.smoke.cleanup.enabled=true" verify
```

实际结果：Failsafe 运行 1 项、通过 1 项，`BUILD SUCCESS`。测试仅创建 `wavepilot_knowledge_smoke_v1`，写入 5 份示例文档：

- `THEORY`
- `STANDARD`
- `EXPERIMENT_RECIPE`
- `MATLAB_GUIDE`
- 无关的 `FAILURE_CASE`

测试覆盖并通过：无 metadata 过滤、只过滤 `THEORY`、只过滤 `EXPERIMENT_RECIPE`、只过滤 `POLAR_CODE_K_IDENTIFICATION`、`documentType + experimentType` 联合过滤。无关文档被赋予更高向量相似性，但在 `THEORY` 过滤下仍不会返回。

返回值断言了稳定的 `chunkId`、`documentId`、`title`、`source` 与 `KB[...]` 引用。Milvus schema 和测试向量均为 1024 维。这里使用的是确定性固定 1024 维测试向量，不是 DashScope Embedding；真实 DashScope 维度仍需单独 profile 验证。

本轮通过显式 `wavepilot.smoke.cleanup.enabled=true` 清理了 smoke collection。清理逻辑再次核对名称，并拒绝 `biz` 和正式 `wavepilot_knowledge_v1`。默认值是 `false`；没有显式开启时，已存在的 smoke collection 会使测试失败而不会自动删除。

L2 距离对外显示为 `similarityScore = 1 / (1 + L2 distance)`。它只是保序、便于展示的单调变换，不是概率，也不是置信度。

## DashScope smoke（额度受控，演示前最小执行）

用户于 2026-08-06 将 Phase 3.5 按“有条件完成”验收。真实 DashScope smoke 因当前 API 额度有限暂缓，不重复调用已经由 Stub、Fake、Java Validator 和工具契约测试覆盖的异常场景。

独立 profile 已收缩为以下三个最小验证：

1. 一次短中文文本 Embedding，断言维度为 1024；
2. 一次完整自然语言 `ExperimentSpec` 解析；
3. 一次 Agent 请求，先调用 `searchExperimentKnowledge`，再提交一个 Mock 实验，返回知识引用、jobId 和 `mock=true`。

Agent smoke 使用固定只读知识 Repository，避免为了预置知识或向量查询额外消耗 Embedding 额度；Agent 本身仍使用真实 DashScope Chat 和真实工具调用链。缺参、非法参数、非法 JSON 和工具安全边界继续由默认离线测试保障。

本机 Process、User、Machine 三层环境都没有 `DASHSCOPE_API_KEY`，因此本轮没有执行该 profile，以上只能描述为演示前最小测试已实现，不能描述为真实 DashScope 已通过。

API Key 只能在运行进程的环境变量中设置。不要将值写入 YAML、测试代码、命令脚本、日志或本文档，也不要把真实值粘贴到聊天中：

```powershell
$env:DASHSCOPE_API_KEY = Read-Host "DASHSCOPE_API_KEY"
mvn -B -Pdashscope-smoke verify
Remove-Item Env:DASHSCOPE_API_KEY
```

## 服务收尾

smoke 完成后只停止本项目的 Compose 服务，不自动删除 volume 或正式 collection：

```powershell
docker compose -f vector-database.yml stop
```

## 当前结论

- Java 17 本地容器：真实通过。
- GitHub Actions：未真实运行。
- Milvus 2.5.10：真实通过，固定 1024 维测试向量。
- DashScope Chat/Embedding/Agent：缺少环境变量密钥，未真实运行。
- MATLAB：按范围要求未实现、未测试。

已知待办：真实 DashScope Embedding 维度、自然语言 Spec 解析、ReactAgent 工具闭环以及 GitHub Actions 远程 Run 尚未验证。按用户确认，Phase 3.5 以“有条件完成”封板，可以进入 Phase 4；前三项仅在最终项目演示前执行上述最小 smoke，GitHub Actions 等配置远端后再验证。
