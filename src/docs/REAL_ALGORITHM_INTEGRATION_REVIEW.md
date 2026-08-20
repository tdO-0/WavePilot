# Phase 4.5 简化极化码算法接入审查

审查日期：2026-08-06

输入包：`wavepilot_simple_polar_algorithm.zip`
输入包 SHA-256：`2FF083F73B11836083D11118B1CD34A2FA793610DB0CF7CC2FC5B36024A0069A`

## 审查结论

压缩包路径安全，没有绝对路径、目录穿越或可执行文件，包含：

- `wavepilot_polar_k_identification_simple.m`
- `experiment-spec-smoke.json`
- `README.md`

源码与 README 描述一致，可以作为 WavePilot Phase 4.5 的**简化极化码码维数识别基线**接入。它不是论文复现、不是创新算法，也没有经过标准验证。正式标识固定为：

- algorithmName：`polar-bsc-binomial-k-baseline`
- algorithmVersion：`1.0.0`
- algorithmValidated：`false`
- templateVersion：`polar-k-identification-simple-v1`

## 原算法主入口与调用关系

原始入口：

```matlab
wavepilot_polar_k_identification_simple(inputFile, outputDirectory)
```

原文件内部调用链：

```text
读取和校验 JSON
  -> polar_generator_matrix
  -> bec_reliability_order
  -> Monte Carlo / BSC 参数扫描
     -> 极化编码 X = U * G over GF(2)
     -> BSC 翻转 Y = X + E over GF(2)
     -> estimate_k_binomial
        -> 逆变换 Uhat = Y * G over GF(2)
        -> 各列 zeroCount
        -> 冻结列/信息列二项对数似然
        -> 沿可靠性排序计算 cumulativeScore
        -> 最大似然选择 K=1,...,N-1
  -> CSV / JSON / MAT / PNG / log
```

## 核心统计逻辑核对

以下逻辑与用户说明一致，并在接入时保持不变：

1. `F=[1 0;1 1]`，通过 Kronecker 积构造 `G_N`；
2. 使用设计擦除率 0.5 的 BEC 巴氏参数递推排序信息位；
3. 固定 `trueK=15N/32`；
4. `U(:,infoSet)` 为随机信息位，冻结位为 0；
5. `X=mod(U*G,2)` 完成极化编码；
6. `E=rand(M,N)<epsilon`，`Y=mod(X+E,2)` 实现 BSC；
7. `Uhat=mod(Y*G,2)` 执行逆向极化变换；
8. 对 `zeroCount` 构造 frozen/info 二项对数似然；
9. 按 `reliabilityOrder` 累积得分并最大化得到 `kHat`；
10. 每个参数点 `accuracy=correctCount/monteCarloTimes`。

## 接入后的函数关系

原单文件被拆成固定编排层和算法层，公式没有改变：

```text
run_experiment.m
  -> load_and_validate_spec.m
  -> run_parameter_sweep.m
     -> run_single_case.m
        -> algorithm/polar_generator_matrix.m
        -> algorithm/bec_reliability_order.m
        -> algorithm/estimate_k_binomial.m
  -> export_results.m
  -> plot_results.m
```

允许的适配仅包括入口名称、JSON 读取、目录结构、日志、进度和产物导出。

## ExperimentSpec 字段映射

| 字段 | MATLAB 含义 |
|---|---|
| `experimentType` | 必须为 `POLAR_CODE_K_IDENTIFICATION` |
| `codeLengths` | 母码长 N；支持 32、64、128、256、512 |
| `errorRateStart/End/Step` | BSC 比特翻转概率扫描；不是 SNR |
| `sampleCount` | 每次独立实验截获的完整码字数量 M |
| `monteCarloTimes` | 每个 N/errorRate 参数点的独立重复次数 T |
| `randomSeed` | MATLAB `rng(seed,'twister')` 的种子，范围 0 至 2^32-1 |
| `outputTypes` | Java 侧声明需要登记和校验的产物 |
| `description` | 仅用于审计，不进入命令或算法分支 |

真实 K 规则：

| N | K |
|---:|---:|
| 32 | 15 |
| 64 | 30 |
| 128 | 60 |
| 256 | 120 |
| 512 | 240 |

## 输入包中需要适配但不能改变的部分

不能改变：生成矩阵的 Kronecker 顺序、BEC 排序递推、信息位选择、GF(2) 编码/BSC/逆变换、有效翻转概率公式、二项对数似然、候选 K 范围和最大似然决策。

已适配：JSON 校验、固定模板入口、参数扫描进度、严格 CSV/summary 契约、MAT 数值矩阵导出、PNG 绘图和日志格式。

## 教学 fixture 分离

Phase 4 能量阈值模板没有删除，现位于：

```text
matlab/templates/polar-k-integration-fixture-v1/
```

它标记为 `INTEGRATION_FIXTURE`、`algorithmValidated=false`。其中 `mock=false` 只表示真实 MATLAB 进程，只用于 Runner、超时、取消和 Artifact 集成测试，不作为极化码算法结果。

正式简化极化码模板位于：

```text
matlab/templates/polar-k-identification-simple-v1/
```

两者由 Java 白名单选择，不能通过 Agent 或 ExperimentSpec 指定任意脚本。

## 缺失项与算法边界

提供包本身是完整的简化基线，没有缺失本地函数或数据文件。它不依赖额外 toolbox。

仍然缺少论文/标准算法的理论验证、公开数据集对照、大规模统计置信区间和标准通信链路复现。因此可以在简历中描述为“接入并验证简化极化码码维数识别基线”，不得描述为提出新算法、论文复现或达到标准性能。
