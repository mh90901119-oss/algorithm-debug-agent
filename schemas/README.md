# Schemas

- `execution/run-outcome-summary-v1.schema.json`：面向模型的一次目标 UT 运行结构化摘要；原始内容通过不可变 Artifact 引用按需读取。
- `execution/run-request-v1.schema.json`：启动 Maven 前必须先落盘的 Run 请求。
- `execution/run-result-fingerprint-v1.schema.json`：一次 Run 的 Gantt 内容与目标失败确定性指纹；
  Gantt 原始/JSON Token Hash 成对出现，且至少存在一种目标观察。
- `tool/tool-response-v2.schema.json`：CLI 与客户端薄适配之间的成功/失败、data 和 Artifact 响应边界。
- `case/case-manifest-v1.schema.json`：Case 的 Project、目标 UT 和初始问题身份。
- `case/context-snapshot-v1.schema.json`：有界源码、POM、输入和 Adapter 上下文快照。
- `case/analysis-request-v1.schema.json`：一次用户问题对应的追加式 Analysis 请求。
- `case/case-digest-v1.schema.json`：查询时重建的有界 Case 历史摘要。

Versioned JSON Schemas for Case, execution, Gantt, collection, trace, evidence and report artifacts.

其他已实现的 Phase 0 Schema：

- `execution/baseline-manifest-v2.schema.json`：运行前 Fingerprint 与运行后语义哈希组成的 Baseline Manifest；
- `case/baseline-verification-v1.schema.json`：多次 Run 的 Baseline 稳定性状态。

新增可选字段保持主版本；破坏性结构变化必须增加新的主版本文件，历史 Schema 不覆盖。
