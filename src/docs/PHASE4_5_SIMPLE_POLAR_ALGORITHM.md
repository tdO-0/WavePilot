# Phase 4.5 简化极化码算法接入报告

更新时间：2026-08-06

## 完成结论

用户提供的简化极化码算法已接入现有 `LocalMatlabExperimentRunner`。Runner、状态机、ResultValidator 和 ArtifactRegistry 主链保持不变；Runner 只增加了固定模板白名单和目录资源复制能力。

正式模板：`polar-k-identification-simple-v1`

算法：`polar-bsc-binomial-k-baseline`

版本：`1.0.0`
边界：`mock=false`、`algorithmValidated=false`、`SIMPLIFIED_BASELINE`

## 算法链路

```text
ExperimentSpec
  -> Java ExperimentSpecValidator
  -> polar-k-identification-simple-v1
  -> polar generator / BEC reliability / polar encoding
  -> BSC bit flips / inverse transform
  -> binomial log-likelihood K estimation
  -> parameter sweep and Monte Carlo
  -> CSV / summary / MAT / PNG / log
  -> RealPolarAlgorithmResultValidator
  -> ArtifactRegistry + SHA-256
```

完整算法审查见 `REAL_ALGORITHM_INTEGRATION_REVIEW.md`。

## 真实 MATLAB smoke

- MATLAB：23.2.0.2365128（R2023b）
- Job：`JOB-1F584725-394`
- codeLengths：32、64
- BSC errorRate：0、0.05、0.10
- sampleCount M：50
- monteCarloTimes T：10
- randomSeed：20
- 参数点：6/6
- minAccuracy：0.5
- maxAccuracy：1.0
- meanAccuracy：0.8333333333333334
- MATLAB IT：1/1 通过
- 同轮默认离线测试：63/63 通过（随后新增随机种子上界回归，总数变为 64）

产物目录：

```text
<project-root>\smoke-artifacts\matlab\JOB-1F584725-394
```

| 产物 | SHA-256 |
|---|---|
| `accuracy.csv` | `0B52FBD953386251C2FCEF2D53939A25FB10F32A5AD6D7B23B5A40572F0000D2` |
| `summary.json` | `80260D63C84D277603EA91B850DC43CDCA7399BD5F167BC204781232BA4C5F86` |
| `result.mat` | `A96C447B8F586488CB3DC026EC677D44C418FADA6D5720EDB42061A37C7CEB69` |
| `accuracy-curve.png` | `CA722F6238927695F833AB4362B43B2C324BCDACEF1C7B8E923F3491BB412FBB` |
| `run.log` | `11339DC38724545FD9F7998DDE7003B29A382CABCC6500320040DEF601724345` |

## accuracy.csv

```csv
codeLength,trueK,errorRate,correctCount,monteCarloTimes,accuracy,sampleCount,randomSeed,meanEstimatedK,mae,bias,runtimeSeconds,algorithmVersion
32,15,0,10,10,1,50,20,15,0,0,0.0419663,1.0.0
32,15,0.05,9,10,0.9,50,20,14.9,0.1,-0.1,0.002044,1.0.0
32,15,0.1,6,10,0.6,50,20,15.1,0.7,0.1,0.0011594,1.0.0
64,30,0,10,10,1,50,20,30,0,0,0.0077932,1.0.0
64,30,0.05,10,10,1,50,20,30,0,0,0.0039471,1.0.0
64,30,0.1,5,10,0.5,50,20,31,1,1,0.0029655,1.0.0
```

运行时间列是本机单次观测值，不作为跨机器性能结论。

## summary.json 关键内容

```json
{
  "experimentType": "POLAR_CODE_K_IDENTIFICATION",
  "algorithmName": "polar-bsc-binomial-k-baseline",
  "algorithmVersion": "1.0.0",
  "templateVersion": "polar-k-identification-simple-v1",
  "runnerType": "local-matlab",
  "mock": false,
  "algorithmValidated": false,
  "classification": "SIMPLIFIED_BASELINE",
  "errorRateMeaning": "BSC_BIT_FLIP_PROBABILITY",
  "trueKRule": "15N/32",
  "randomSeed": 20,
  "totalPoints": 6,
  "completedPoints": 6,
  "minAccuracy": 0.5,
  "maxAccuracy": 1,
  "meanAccuracy": 0.8333333333333334,
  "success": true
}
```

## ResultValidator 与 Artifact

严格校验已真实通过：

- `trueK=15N/32`；
- `0 <= correctCount <= T`；
- `accuracy=correctCount/T`；
- N/errorRate 网格完整且无重复；
- CSV/Manifest/summary 算法版本一致；
- `mock=false`、`algorithmValidated=false`；
- summary min/max/mean 与 CSV 一致；
- completedPoints=6；
- MAT 含 `accuracyMatrix`、`estimatedKMatrix`、`NVec`、`errorVec`、`trueKVec`；
- PNG 可解码、非空白，并由模板直接使用同一 Accuracy 矩阵绘制；
- 5 个运行产物均由 ArtifactRegistry 登记大小和 SHA-256，加上 spec/plan 共 7 项。

## 测试分层

普通 `mvn -B clean test` 不启动 MATLAB 或访问网络。Phase 4 原 55 项全部保留，新增八类要求测试和随机种子上界回归后共 64 项。

最终 Java 17 回归使用 Docker 镜像 `maven:3.9.9-eclipse-temurin-17`，实际 JVM 为 Eclipse Temurin 17.0.15，执行规定命令：

```text
mvn -B clean test
Tests run: 64, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Windows 主机 Oracle JDK 22.0.2 下同样为 64/64 通过；两次构建均以 `javac --release 17` 编译。GitHub Actions 没有远程 run，不能将本地验证描述为 GitHub Actions 已通过。

真实 MATLAB 仅通过 `matlab-smoke` profile 执行：

```powershell
mvn -B -Pmatlab-smoke "-Dwavepilot.runner.local-matlab.executable=D:\Program Files\MATLAB\R2023b\bin\matlab.exe" verify
```

## Fixture 与正式基线

| 项目 | 正式简化基线 | Integration fixture |
|---|---|---|
| 模板 | `polar-k-identification-simple-v1` | `polar-k-integration-fixture-v1` |
| 极化编码 | 有 | 无 |
| BSC + 逆变换 + 二项似然 | 有 | 无 |
| classification | `SIMPLIFIED_BASELINE` | `INTEGRATION_FIXTURE` |
| algorithmValidated | false | false |
| 用途 | 项目业务基线展示 | Runner/超时/取消/Artifact 测试 |

两者都不是论文算法或标准验证算法。
