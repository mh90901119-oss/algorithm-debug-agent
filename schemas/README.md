# Schemas

- `execution/run-outcome-summary-v1.schema.json`：面向模型的一次目标 UT 运行结构化摘要；原始内容通过不可变 Artifact 引用按需读取。

Versioned JSON Schemas for Case, execution, Gantt, collection, trace, evidence and report artifacts.

Phase 0 implemented schemas:

- `execution/baseline-manifest-v2.schema.json`：运行前 Fingerprint 与运行后语义哈希组成的 Baseline Manifest；
- `case/baseline-verification-v1.schema.json`：多次 Run 的 Baseline 稳定性状态。

新增可选字段保持主版本；破坏性结构变化必须增加新的主版本文件，历史 Schema 不覆盖。
