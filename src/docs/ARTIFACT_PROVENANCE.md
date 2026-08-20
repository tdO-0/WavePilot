# Artifact Provenance

Phase 5A 将每个报告数值追溯到已通过 `ResultValidator` 的 Artifact。`ArtifactRecord` 保存 Job、类型、Runner、Mock/算法边界、相对路径、SHA-256、大小、MIME、模板/算法版本、validated 和创建时间。

绝对路径只作为 `@JsonIgnore` 的服务内部定位信息存在。metadata API 只返回 `relativePath`；下载与校验会重新解析真实路径，拒绝目录穿越、符号链接、Job 目录之外文件以及哈希或大小变化。

`ArtifactCitation` 使用 `artifactId + artifactSha256 + rowReference + fieldName + value` 定位原始值。CSV 的 `rowReference` 是从 1 开始的数据行；JSON 使用 `$` 与顶层字段名。引用状态 `VERIFIED/PARTIAL/UNVERIFIED` 只表示引用完整性，不是模型置信度。

真实 MATLAB、Mock 与算法验证是三条不同轴：`mock=false` 表示真实 Runner 进程；`classification=SIMPLIFIED_BASELINE` 表示算法类别；`algorithmValidated=false` 表示没有科研性能验证。
