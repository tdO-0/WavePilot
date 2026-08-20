# Controlled Report Agent Design

模板报告是默认且完整的实现，不需要 DashScope。可选 `ReportLanguageModel` 只能收到不可变的 `ExperimentReportData` 与模板 Markdown；接口没有文件系统、ProcessBuilder、MATLAB、Runner、Repository、Artifact 写接口或工具执行能力。

`ControlledReportAgent` 要求模型保留全部 `ReportConclusion`、Citation ID 和以下边界：`mock=false`、`SIMPLIFIED_BASELINE`、`algorithmValidated=false`、不能作为论文复现结果。模型生成不存在于模板数据的数值、修改 Conclusion、删除引用或删除边界时均拒绝输出。

模型异常、超时、额度不足或边界校验失败时，`ReportService` 自动返回 `TEMPLATE_FALLBACK`。默认工程没有真实 Report Model Bean，离线测试使用 Stub/Fake，不调用 DashScope。
