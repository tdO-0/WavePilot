# 模板安全模型

更新时间：2026-08-06

## 分层结论

安全检查输出三层：**BLOCKED**（禁止发布）、**WARNING**（需人工审查）、**PASSED**（无 BLOCKED）。任何 BLOCKED 都会阻止候选进入 SMOKE_PENDING，发布时再次复查。

## 禁止项（MATLAB 高危调用，BLOCKED）

| 规则 | 覆盖 |
|---|---|
| MAT-001/002/003/004 | system / ! / unix / dos 系统命令 |
| MAT-005/006 | webread/websave/urlread/urlwrite/tcpclient/udpport/ftp 网络访问 |
| MAT-007 | delete / rmdir 删除操作 |
| MAT-008 | eval / evalin / assignin / feval 动态执行 |
| MAT-009 | java / py.* 外部运行时 |

## 警告项（WARNING，需人工确认）

movefile/copyfile 目标范围、fopen 绝对路径、cd 外部目录、addpath 外部路径、load/save 外部路径、疑似绝对路径引用。

## 路径与文件边界

- 模型产出的每个相对路径必须规范化：拒绝绝对路径（`/` 或盘符）、`..`、反斜杠、隐藏路径绕过。
- 候选写入仅限 `candidates/<candidateId>/`；发布写入仅限 `approved/<templateId>/<version>/`。
- templateId/version 路径段白名单校验，拒绝 `..`、分隔符、冒号。
- 文件数量 ≤ 50，单文件 ≤ 1 MiB，总包 ≤ 5 MiB；重复文件名覆盖被拒绝。
- 所有文件计算 SHA-256；发布前重算比对，篡改即拒。

## 能力边界

- 不做动态 JAR、SPI 扫描、ClassLoader、运行时编译 Java、任意类名反射。
- 不做任意 Shell 执行；MATLAB 只通过固定入口 `run_experiment('matlab-input.json', '.')` 运行版本化模板。
- Agent 只有 7 个受控模板工具（列表/详情/候选/生成/校验/请求 Smoke），**没有**审批、激活、删除、发布工具。
- 审批必须来自显式用户动作（REST 或前端按钮，携带 approvedBy）。

## 验证边界

- `operationalValidated`（能安全运行并产出合法产物）与 `algorithmValidated`（经过人工或科研验证）是两条独立轴。
- 模板能运行 ≠ 算法经过科学验证；Replay 一致 ≠ 算法正确。
- 生成模板默认 `algorithmValidated=false`、`SIMULATION_BASELINE`，前端与 API 全程明示。
