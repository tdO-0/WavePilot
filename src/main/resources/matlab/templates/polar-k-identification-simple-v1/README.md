# polar-k-identification-simple-v1

固定的 WavePilot Phase 4.5 简化极化码码维数识别模板。

它使用极化生成矩阵、BEC 巴氏参数信息位排序、BSC 比特翻转、逆向极化变换和二项分布对数似然估计 K。核心公式来自用户提供的 `wavepilot_polar_k_identification_simple.m`，适配仅拆分了 JSON 读取、参数扫描和产物导出。

该算法名称为 `polar-bsc-binomial-k-baseline`，版本 `1.0.0`。它不是论文复现、不是创新算法，也没有经过标准验证，因此所有结果必须保留 `algorithmValidated=false`。

`errorRate` 是 BSC 比特翻转概率，不是 SNR；`sampleCount` 是每次 trial 截获的完整码字数 M；`monteCarloTimes` 是每个参数点的独立重复次数 T；真实 K 固定为 `15N/32`。
