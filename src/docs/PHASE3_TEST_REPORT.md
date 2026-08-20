# Phase 3 测试报告

测试日期：2026-08-06

## 结论

在项目根目录执行规定命令 `mvn -B clean test`，Windows 主机与本地 JDK 17 容器均构建成功：

```text
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

编译了 90 个主源码文件与 24 个测试源码文件，编译日志为 `javac [debug release 17]`。原有 48 项全部继续通过，新增 1 项 smoke collection 配置安全单元测试。

## 运行环境

| 项目 | 值 |
|---|---|
| 操作系统 | Windows 11 amd64 |
| Maven | 3.9.9 |
| Maven 编译目标 | Java 17 / `--release 17` |
| Windows 本地测试 JVM | Oracle JDK 22.0.2，49/49 通过 |
| Docker 本地测试 JVM | Eclipse Temurin 17.0.15，49/49 通过 |
| GitHub Actions 目标 JVM | Temurin JDK 17 |
| GitHub Actions 状态 | 工作流已配置，尚未实际运行 |

Windows 结果证明源码按 Java 17 字节码/API 目标编译并在 JDK 22 上通过；另用 `maven:3.9.9-eclipse-temurin-17` 容器实际运行了同一命令，测试日志确认 Java 17.0.15。首次缓存核验运行耗时 8 分 03 秒，密钥日志修复后当前最终源码的干净复跑耗时 2 分 00 秒，两次均通过。项目已初始化本地 Git，但没有 GitHub remote 且没有 push，因此 `.github/workflows/java17-ci.yml` 没有真实 CI run 可引用。

## 覆盖范围

- Phase 0–2 回归：参数校验、状态机、Mock Runner、结果校验、ArtifactRegistry、SSE、报告引用前置契约与 Replay 指纹。
- 自然语言 Spec：完整输入、缺参追问、默认值披露、无关输入、模型错误和 Java 二次校验。
- Agent：10 个工具名称与依赖边界、Prompt 安全契约、普通与 SSE Controller 契约。
- Knowledge：metadata、稳定 ID/引用、Milvus 标量过滤表达式、上传与搜索 Controller 契约。
- Legacy：默认关闭条件和旧 Controller 条件注解。
- Collection 安全：可配置独立 collection、拒绝 legacy `biz` 与非法名称。

## 外部验证

- Milvus 2.5.10 standalone：独立 `wavepilot_knowledge_smoke_v1` 真实建表、写入 5 份样例、5 类 metadata 检索、1024 维 schema、稳定字段和受控清理均通过；Failsafe 1/1 通过。
- Milvus 测试使用固定 1024 维向量，不是 DashScope Embedding。
- 未调用真实 DashScope Chat、Embedding 或 Agent，因为本机没有 `DASHSCOPE_API_KEY` 环境变量；独立 profile 已实现但未执行。
- 未运行 MATLAB；实验结果来自 Mock Runner。
- 未实际执行 GitHub Actions Java 17 job。

外部命令与边界详见 `docs/PHASE3_5_EXTERNAL_SMOKE.md`。

Phase 3.5 已由用户按“有条件完成”验收。最终演示前只执行一次 Embedding、一次完整 Spec 和一次检索后提交 Mock 实验的 Agent smoke；缺参、非法参数、非法 JSON 与工具安全边界继续由离线测试保障。

## 已知提示

构建仍提示 legacy `VectorSearchService` 使用已过时 API。该提示不影响 Phase 3 结果，且不来自 WavePilot 新的 JDK HTTP Client 配置；后续清理旧向量服务时处理。
