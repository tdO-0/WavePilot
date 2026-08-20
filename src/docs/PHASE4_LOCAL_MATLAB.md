# Phase 4 Local MATLAB Runner

> 本文记录 Phase 4 的历史验收快照。Phase 4.5 已将该能量阈值算法迁移为 `polar-k-integration-fixture-v1`，当前正式本地模板和最新结果见 `PHASE4_5_SIMPLE_POLAR_ALGORITHM.md`。

更新时间：2026-08-06

## 验收结论

Phase 4 的本地 MATLAB 链路已实现并真实通过 MATLAB R2023b smoke：

```text
ExperimentSpec
  -> Java Validator
  -> fixed template local-matlab-polar-k-v1
  -> LocalMatlabExperimentRunner
  -> ProcessBuilder
  -> timeout / cancel / progress / run.log
  -> CSV / MAT / PNG / summary
  -> ResultValidator
  -> ArtifactRegistry + SHA-256
```

本轮没有实现 MCP MATLAB、报告 Agent、Replay、Eval、MySQL、新前端，也没有调用 DashScope。

## Mock 与真实边界

| 层次 | 结论 |
|---|---|
| Java 编排 | 真实实现：状态机、异步执行、超时、取消、日志和产物登记 |
| MATLAB 进程 | 真实：本机 MATLAB 23.2.0.2365128（R2023b）实际启动并正常退出 |
| CSV/MAT/PNG | 真实：由该 MATLAB 进程生成，随后由 Java 校验和计算 SHA-256 |
| 算法 | 固定的能量阈值 Monte Carlo 教学基线，不是标准验证过的极化码译码器 |
| 默认运行模式 | `mock`；不会在普通离线测试中启动 MATLAB |
| Local 模式 | 只有可信主机显式配置 `local-matlab` 和 MATLAB 可执行文件后才启用 |

`summary.json` 必须包含显式布尔字段 `mock`。本轮真实 MATLAB 产物为 `mock=false`、`runner=local-matlab`，同时用 `simulationBoundary` 写明算法限制。

## 安全设计

1. MATLAB 命令固定为 `wavepilot_polar_k_identification('matlab-input.json', '.')`。
2. 用户描述、参数和模型输出永远不进入命令行，只能进入 Java 校验后的 `matlab-input.json`。
3. 模板从应用 classpath 复制，不接受用户脚本路径、函数名或 MATLAB 表达式。
4. 作业使用 ArtifactRegistry 的独立 job 目录；产物路径在登记时再次做目录边界检查。
5. 总超时和取消都会终止 MATLAB 进程树；取消发生在进程启动前时仍保留预创建的 `run.log`。
6. MATLAB 返回 0 只代表进程退出成功。Java 还会检查 CSV 完整网格、缺失/额外点、准确率范围、summary 一致性、MAT 签名、PNG 签名及可解码尺寸和必需文件。

这不是操作系统级 CPU/内存沙箱。生产环境若需要运行不受信任的算法，应放入容器或独立执行节点；当前固定模板模式不接受不受信任代码。

## 配置与命令

默认配置：

```yaml
wavepilot:
  runner:
    type: mock
```

显式启用本机 MATLAB：

```powershell
$env:WAVEPILOT_RUNNER_TYPE = "local-matlab"
$env:MATLAB_EXECUTABLE = "D:\Program Files\MATLAB\R2023b\bin\matlab.exe"
$env:WAVEPILOT_MATLAB_TIMEOUT = "10m"
mvn spring-boot:run
```

独立真实 smoke 默认不执行，命令为：

```powershell
mvn -B -Pmatlab-smoke "-Dwavepilot.runner.local-matlab.executable=D:\Program Files\MATLAB\R2023b\bin\matlab.exe" verify
```

普通离线回归仍是：

```powershell
mvn -B clean test
```

它只使用 Mock/Fake/Stub，不连接 DashScope、Milvus 或 MATLAB。

## 真实 smoke 结果

执行时间：2026-08-06；最终验收 job：`JOB-5629AA18-5BD`。

- MATLAB：23.2.0.2365128（R2023b）
- 参数：码长 32/64，误码率 0–0.30、步长 0.05，sampleCount 24，Monte Carlo 20，randomSeed 20260806
- 结果点：14
- 平均识别准确率：0.6
- Failsafe：1 项，失败 0，错误 0，跳过 0，`BUILD SUCCESS`
- 该次 profile 同时执行默认离线测试：55 项全部通过
- MATLAB smoke 的本地 Maven JVM：Oracle JDK 22.0.2；项目编译目标仍是 Java 17
- Eclipse Temurin 17.0.15 容器实际执行 `mvn -B clean test`：55/55 通过，`BUILD SUCCESS`
- GitHub Actions：仍未产生远程 Run，不能把本地容器结果称为 Actions 通过

产物目录：

```text
<project-root>\smoke-artifacts\matlab\JOB-5629AA18-5BD
```

| 产物 | SHA-256 |
|---|---|
| `accuracy.csv` | `6E3B11DF6443319359915225A623FC4919DF76CCDF7DF937ED5575AA245B0B11` |
| `result.mat` | `264A5D3FB497BFDF57A4B31191B3BD20C1F70BFE08936ECBA0D0FC3DAEE802DA` |
| `accuracy-curve.png` | `2C74D161F77B13F1D03533164E0440EA0C776BEA6A98BDA79650EB1D967B4416` |
| `summary.json` | `17849964BB71E303567124BB2C0152967774240B9EF6EBDCEEB03C2AA7556FA1` |
| `run.log` | `814A9D85E2443F56A30DD5F79C5E042C7EF47A6B8D844C261469795363F54E79` |

同一 randomSeed 下 CSV 和 summary 可复现；MAT/PNG 可能包含格式层面的元数据，因此验收依赖内容校验与每次运行重新登记的 SHA-256，而不是要求跨运行哈希固定。

## 测试分层

- 默认单元/契约测试：55 项，不依赖外部网络，覆盖固定命令、用户内容不进入命令、成功、超时、运行中取消、立即取消、MAT/PNG 签名和结果网格校验。
- MATLAB smoke：独立 `matlab-smoke` profile，真实启动 MATLAB，默认不执行。
- DashScope/Milvus：沿用各自独立 profile；本轮没有调用。

## 面试说明建议

可以表述为：“我把 LLM 负责的意图理解与 Java 负责的确定性校验、MATLAB 负责的数值计算分开；Agent 无法接触 ProcessBuilder。真实 MATLAB 使用固定版本模板，Java 负责超时、取消、结果一致性和产物追踪。”

不要表述为：“已经实现生产级极化码译码器”或“MATLAB 结果证明算法达到某标准性能”。当前可证明的是执行平台闭环与教学基线的可复现性。
