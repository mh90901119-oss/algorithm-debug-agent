# ADR-001：动态输出发现与两阶段 Case 身份

- 状态：Accepted（身份作用域条款由 ADR-015 修订）
- 日期：2026-08-11

> 当前状态：动态输出 provenance 仍有效；Context 身份与基线作用域已由 ADR-015 删除并改为 Analysis 作用域。

## 背景

目标算法 UT 自己读取输入，并向固定目录写出名称动态的调度结果。仅选择“最新文件”无法证明结果来自
本次运行；把时间戳格式写入 Adapter 又会把目标项目细节提升为通用契约。同时，现有
`ExecutionIdentity` 包含运行后结果哈希，不能用于运行前选择 Case。

## 决策

1. Adapter 暴露 `ScheduleResultSource` 输出目录，不选择具体结果文件；
2. Debug Harness 用运行前后目录快照差分建立本次运行 provenance，并用 Adapter Parser 验证候选；
3. 引入运行前 `CaseFingerprint`，`ExecutionIdentity` 由 Fingerprint 与运行后语义哈希组成；
4. 每个 Run 的原始产物不可覆盖，canonical baseline 只保存引用与稳定性统计；
5. 相同 Fingerprint 结果不同标记 Baseline 不稳定，不自动创建新 Case；
6. LLM 只提交 Case Intent，确定性 Case Resolution Service 决定复用、新建或 Revision。

## 影响

- `adapter-sdk` 的主要 SPI 在 0.1.0 阶段发生破坏性调整；
- `BaselineManifest` Schema 升级到 2.0；
- `debug-harness` 和 `case-management` 成为 Baseline 垂直链路的确定性事实层；
- 目标算法文件名、时间戳格式和目录历史不再进入 Agent Core。

## 被否决方案

- 直接取目录最新文件：UT 失败时可能误读旧结果；
- 强制 `yyyyMMddHHmmss.json`：无法迁移到其他算法；
- 相同身份结果变化时自动新建 Case：会掩盖算法非确定性；
- 让 LLM 直接读写 Case 状态：缺乏确定性和审计边界。

## 后续修订

`ADR-006-case-as-analysis-dossier.md` 将“同一 UT 的源码、输入或环境 Fingerprint 变化创建 Revision Case”
收敛为“同一用户问题的 Case 内追加 Context Snapshot”。本 ADR 的两阶段身份、动态输出 provenance 和
采集一致性原则继续有效，但 Fingerprint 现在界定 Run/Evidence 的 Context 作用域，不再单独决定同一
问题是否拆分 Case。目标 UT selector 改变或调用方明确开始独立问题时，仍默认创建新 Case。
