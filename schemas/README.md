# Schemas

- `execution/run-outcome-summary-v1.schema.json`：面向模型的一次目标 UT 运行结构化摘要；原始内容通过不可变 Artifact 引用按需读取。
- `execution/run-request-v1.schema.json`：启动 Maven 前必须先落盘的 Run 请求。
- `execution/run-result-fingerprint-v1.schema.json`：一次 Run 的 Gantt 内容与目标失败确定性指纹；
  Gantt 原始/JSON Token Hash 成对出现，且至少存在一种目标观察。
- `tool/tool-response-v2.schema.json`：CLI 与客户端薄适配之间的成功/失败、data 和 Artifact 响应边界。
- `case/case-manifest-v2.schema.json`：Case 的 Project、目标 UT、冻结 Adapter 和初始问题身份。
- `case/context-record-v2.schema.json`：显式分析版本身份；不包含源码、输入、POM 或环境快照。
- `case/analysis-request-v1.schema.json`：一次用户问题对应的追加式 Analysis 请求。
- `case/case-digest-v1.schema.json`：查询时重建的有界 Case 历史摘要。
- `analysis/method-catalog-v2.schema.json`：从目标 UT 出发生成的有界方法目录、静态调用边、方法源码锚点与截断信息。
- `collection/codepath-plan-v2.schema.json`：大模型选择关键方法后，由确定性编译器生成的精确 class/method/descriptor 采集计划。
- `collection/method-path-manifest-v2.schema.json`：外部方法路径工具的版本、计划 Hash、退出状态、单一 Raw 流计数与截断事实；
  独立保存 `targetOutcome` 与 JUnit 计数，即使采集工具同时失败也不会遮蔽目标 UT 失败；
  `AGENT_FAILED` 与目标/工具失败分离，必须携带结构化 AgentFailure。
- `collection/method-path-collection-request-v1.schema.json`：Collector 启动前追加写入的 Case/Context/Analysis/Run/Plan/Collection 身份。
- `collection/collection-baseline-check-v1.schema.json`：动态采集与同 Context 无采集参考的 Gantt 一致性及证据可用状态。
- `collection/collection-execution-summary-v1.schema.json`：CLI 返回给模型的有界采集状态和 Case 相对产物入口。
- `collection/jdwp-plan-v2.schema.json`：不含模块源码指纹、但每个 tracepoint 必须保留精确 SourceAnchor 的 JDWP 计划。
- `trace/method-path-summary-v2.schema.json`：单线程精确方法事件的有界调用关系、计数、异常与 provenance 摘要。

Versioned JSON Schemas for Case, execution, Gantt, collection, trace, evidence and report artifacts.

其他已实现的 Phase 0 Schema：

- `execution/baseline-manifest-v2.schema.json`：运行前 Fingerprint 与运行后语义哈希组成的 Baseline Manifest；
- `case/baseline-verification-v1.schema.json`：多次 Run 的 Baseline 稳定性状态。

新增可选字段保持主版本；破坏性结构变化必须增加新的主版本文件，历史 Schema 不覆盖。
所有契约时间按 ISO-8601 字符串输出；v2 关键 Schema 使用 Draft 2020-12 测试校验器验证真实序列化实例。
