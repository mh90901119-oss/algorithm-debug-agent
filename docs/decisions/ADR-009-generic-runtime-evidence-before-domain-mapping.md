# ADR-009：先生成通用运行时证据，再按需增加领域映射

- 状态：Proposed
- 日期：2026-08-18

## 背景

CodePathTracer 和 JDWP Collector 已经能够在不修改目标算法源码的前提下采集方法路径、调用栈和局部变量。
早期架构要求 P4 Normalizer 直接生成 `candidate_generated`、`constraint_filtered`、
`candidate_selected`、`schedule_committed` 等晶圆调度事件。这些事件适合当前 Wafer Demo，但公司的大型
算法可能使用完全不同的任务、资源、约束、搜索和结果模型。把这些概念写进通用 Normalizer 会导致：

- 新算法必须修改核心模块；
- 变量名和对象结构变化造成脆弱 Mapping；
- 确定性代码被迫猜测业务含义；
- 未经证据支持的业务推断可能被错误标记为观察事实。

另一方面，把完整 Raw Trace 直接交给大模型会造成上下文、性能、敏感数据和证据完整性风险。需要在二者之间
建立稳定边界。

## 决策

1. P4 通用核心只生成两类结构化事实：
   - CodePath 方法统计、线程、最近保留祖先路径、精度和异常；
   - JDWP tracepoint 命中、线程、源码位置、调用栈和通用有界值路径。
2. 每个派生事实必须引用不可变 Raw Artifact 的 SHA-256 与 JSONL 行号、`eventId` 或 `sequence`。
3. P4 Validator 只校验 Artifact、Schema、身份、Hash、计划、源码、预算、截断、Provenance 和 Baseline；
   不调用 LLM，不判断算法业务正确性。
4. Evidence Bundle 只组织事实、验证状态和覆盖维度。`SUFFICIENT` 只表示声明的证据维度已经覆盖，不表示
   根因已经确认。
5. 大模型结合源码、项目知识、用户问题和 Evidence 解释业务含义，并决定是否继续采集。
6. Wafer 或其他算法的领域映射是可选派生层。出现经过验证的真实需求后，由 Adapter 或独立领域模块消费
   通用 Summary，生成版本化业务投影；不得修改或伪造 Raw Trace。
7. P4 v1 不新增 `DomainMappingProvider` 或任意 Mapping DSL，避免在缺少第二个真实算法验证时提前冻结接口。
8. 大型算法的运行时安全仍由采集计划、Collector 预算和进程监管保证；P4 后处理不能替代 CodePath 源头
   过滤或 JDWP local allowlist/字段投影。

## 影响

- `trace-normalizer` 不依赖 `adapter-sdk`，也不包含晶圆调度字段；
- 早期文档中的固定领域事件不再是 P4 首版验收条件；
- 大模型默认读取通用摘要、Validation 和 Evidence Bundle，必要时按 Provenance 读取 Raw 片段；
- 新算法接入主要提供构建/UT/输入/结果 Adapter 和项目知识，不需要重写 P4；
- 业务语义自动化程度低于固定 Wafer Mapping，但不会把未经证明的映射作为确定性事实；
- 未来领域投影可以从不可变 Raw 和通用 Summary 重放，不需要重新运行目标 UT。

## 被否决方案

- **在通用 Normalizer 中固定 Wafer 领域事件**：对当前 Demo 直观，但不能作为多算法产品边界；
- **只保存 Raw Trace，由大模型直接分析**：缺少有界摘要、完整性门禁和稳定引用；
- **首版实现任意领域 Mapping DSL**：扩展面和安全面过大，缺少真实需求验证；
- **让 LLM 生成 Domain Trace**：不可重复、不可确定性测试，也容易把推测写成观察事实。
