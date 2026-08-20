# 可复现展示案例

该案例用固定 `ExperimentSpec` 和随机种子 `20` 运行确定性离线 Mock 链路，并自动验证：

- 2 个码长 × 3 个错误率，共 6 个参数点；
- 5 个 Artifact 全部通过校验；
- 报告指标与仓库内 `expected/accuracy.csv` 一致；
- Replay 判定为 `REPRODUCIBLE`，accuracy 最大绝对差为 `0.0`；
- `stub-v1` 离线 Eval 通过 24/24 Case，11/11 指标为 `1.0`。

先在仓库根目录启动离线应用：

```powershell
$env:WAVEPILOT_KNOWLEDGE_REPOSITORY = "memory"
$env:WAVEPILOT_EMBEDDING_OFFLINE = "true"
$env:DASHSCOPE_API_KEY = "not-configured"
mvn spring-boot:run
```

另开一个 PowerShell：

```powershell
.\examples\reproducible-showcase\run.ps1
```

若应用不在默认端口：

```powershell
.\examples\reproducible-showcase\run.ps1 -BaseUrl http://localhost:9911
```

脚本校验失败会返回非零退出状态；成功时输出含动态 Job/Replay/Eval 编号的 JSON 摘要。固定期望值见 [`expected/metrics.json`](expected/metrics.json)。

> 这些数字验证的是软件编排、产物契约、Grounding、Replay 与 Eval 链路，不是通信算法科研性能，也不代表真实 MATLAB 已执行。
