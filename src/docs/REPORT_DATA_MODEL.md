# Report Data Model

`ReportDataAssembler` 的输入只有当前 `ExperimentJob`、其中的 `ExperimentSpec/ExperimentPlan` 和 `ArtifactRegistry` 返回的已验证记录。它通过 Registry 的安全解析读取 `accuracy.csv`、`summary.json`、spec 与 plan，不读取 MAT/PNG 的数值，也不允许模型读取文件。

`ExperimentReportData` 包含：实验/算法/版本/classification、mock、algorithmValidated、码长、BSC errorRate 范围、M、T、种子、参数点数、min/max/mean Accuracy、每码长最佳/最差点、每个 errorRate 点的 Accuracy/MAE/Bias/meanEstimatedK、MATLAB/Runner/模板版本、Artifact metadata、Citation 与确定性 Conclusion。

Java 从 CSV 重新计算 min/max/mean，并与 summary 比较。每个展示数值带 Citation ID；趋势由 Java 排序计算，不交给模型推断。
