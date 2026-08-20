# 极化码码维数识别实验配方

## 目标

比较多个码长在给定误码率区间内的识别准确率，并生成可校验、可追溯的实验产物。

## 推荐的学习配置

```json
{
  "experimentType": "POLAR_CODE_K_IDENTIFICATION",
  "codeLengths": [32, 64, 128, 256],
  "errorRateStart": 0.0,
  "errorRateEnd": 0.2,
  "errorRateStep": 0.01,
  "sampleCount": 100,
  "monteCarloTimes": 50,
  "randomSeed": 20,
  "outputTypes": ["ACCURACY_CSV", "RUN_LOG"]
}
```

## 执行步骤

1. 先调用自然语言 Spec 解析接口；缺少必要参数时完成追问。
2. 调用确定性 Java 校验，确认参数合法并阅读资源风险警告。
3. 创建计划，检查误码率点数和预计工作单元。
4. 提交异步任务，通过状态接口或 SSE 观察进度。
5. 任务成功后读取 ArtifactRegistry 登记的 CSV、summary 与日志。
6. 报告中引用具体 Artifact ID，不根据聊天记忆编造数值。

## 当前边界

Phase 3 的 runner 类型是 `mock`，这套配方用于验证平台闭环。其准确率不是 MATLAB 或真实信道仿真结果。
