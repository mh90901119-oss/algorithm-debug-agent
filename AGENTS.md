# Algorithm Debug Agent 仓库级开发指令

本文件是 Codex/OpenCode 在本仓库工作的长期强制规则。详细解释、检查清单和例外条件见
`docs/development/development-rules.md`；实现新能力前使用
`docs/designs/implementation-design-template.md` 编写或更新可实施设计。

## 1. 产品与证据边界

- 本项目是离线算法问题定位 Agent，不是在线生产调度器，不得接管生产设备或生产决策。
- 默认不得修改目标算法生产源码来增加 Trace；优先使用外部 JUnit Launcher、静态分析、
  Code Path Tracer 和 JDWP Collector。
- LLM 只负责规划、证据充分性判断和解释。解析、采集、校验、哈希、关联等确定性能力必须由代码实现。
- 结论必须区分 `CONFIRMED_FACT`、`VALIDATOR_CONCLUSION`、`SOURCE_INFERENCE`、
  `LLM_HYPOTHESIS` 和 `MISSING_EVIDENCE`，不得把推测写成事实。
- 动态采集不得使用 Gantt 内容 SHA 作为通用门禁。目标 UT 已失败时，只比较结构化失败指纹；
  `MATCHED` 才可用动态证据确认同类失败，`CHANGED/INCOMPARABLE` 只能作为线索。成功运行的
  Gantt 独立归档并由 LLM 按问题分析。
- Case、Plan、Trace、Evidence、Report 按 `caseId/runId/analysisId` 追加保存，禁止覆盖历史产物。

## 2. 设计先行

- 开始实现前，先查阅 `docs/architecture`、`docs/decisions`、`docs/designs` 和当前阶段计划。
- 新功能、跨模块变更、Schema/CLI/SPI 变更、性能或安全相关变更必须先有可实施详细设计。
- 设计缺失时，先按模板创建文档并完成自审，再写生产代码；不得用聊天记录替代仓库文档。
- 小型缺陷修复可以更新现有设计的“变更记录”，但必须记录根因、行为变化、回归测试和兼容性影响。
- 改变架构边界或引入不可逆技术选型时，同时新增或更新 ADR。
- 实现中如发现设计不可行，先更新设计和决策记录，再继续编码；代码、测试与文档必须一致。
- 流程、状态、时序和模块关系图统一使用 Mermaid；图后必须有文字说明，不能只靠图片表达契约。

## 3. 测试优先

- 行为、缺陷修复、契约和规则实现遵循 Red-Green-Refactor：先写能失败的测试，再写最小实现，最后重构。
- 纯文档、仓库脚手架、机械构建配置和明确标记的技术 Spike 可不先写单元测试，但必须定义并执行可验证检查。
- 测试必须确定、隔离、可重复；单元测试不得依赖网络、真实时间、随机顺序或开发机绝对路径。
- 时间、ID、文件系统、进程和外部 Collector 通过端口或可替换适配器隔离。
- 修复缺陷必须包含能够复现原缺陷的回归测试。
- 优先级为：单元测试 → 契约/Schema 测试 → 模块集成测试 → 关键链路端到端测试 → 性能与 Eval。
- 不得为了让测试通过而削弱断言、删除失败测试或更改与需求无关的 golden 数据。

## 4. Java 与模块设计

- 使用 Java 21、Maven、JUnit 5；遵守单一职责、依赖倒置、接口隔离和高内聚低耦合。
- 面向领域契约建模，避免 God Class、静态可变全局状态、循环依赖和无需求支撑的抽象层。
- `ada-contracts` 不得依赖实现模块；业务 Adapter 不得反向依赖 `ada-core`。
- Collector Adapter 不包含晶圆调度语义；Normalizer 和 Validator 必须确定性执行且不得调用 LLM。
- 跨模块调用优先依赖稳定 SPI/契约；内部实现类型不得泄露为公共 API。
- 公共模型优先不可变；错误必须结构化并保留 cause，不得静默吞异常。
- 新依赖必须说明用途、许可证、版本锁定和替代方案；避免仅为少量工具方法引入大型框架。

## 5. 注释、命名与文档

- 代码标识符、包名、Schema 字段和协议枚举使用清晰英文；面向团队的说明与注释使用中文。
- 公共 API、SPI、核心领域模型和复杂算法必须有中文 Javadoc，说明职责、边界、参数、返回值和异常。
- 行内注释解释“为什么、约束和陷阱”，不复述代码字面含义；简单代码不强制注释。
- TODO 必须包含原因和可追踪标识；禁止长期保留无上下文 TODO、注释掉的代码和调试输出。
- 修改行为、契约、命令或产物结构时，同步更新设计、Schema 示例、README 和使用文档。

## 6. 契约、产物与兼容性

- Case、Plan、Manifest、Raw Trace、Domain Trace、Finding、Evidence Bundle 和 Report 必须有版本化 Schema。
- Schema 演进默认向后兼容；破坏性变更必须升级主版本、提供迁移说明和兼容性测试。
- JSON/JSONL 输出必须稳定、可流式处理并保留 provenance；禁止把无界大对象直接发送给 LLM。
- 原始证据只读保存；Normalizer 生成派生产物，不得回写或伪造 Raw Trace。
- 产物写入优先使用临时文件加原子提交；失败运行也必须保留 manifest、退出码和截断原因。
- 日志和报告不得泄漏凭据、公司敏感路径或未脱敏的生产数据。

## 7. 性能与外部进程安全

- 面向大型算法的采集功能必须在设计中明确事件数、命中数、对象深度、字节数、耗时和队列预算。
- 默认采用 allowlist、投影、topN、采样、流式写盘和有界队列；禁止默认展开完整对象图。
- Agent 启动的测试 JVM、Launcher 和 Collector 必须具备超时、退出码、stdout/stderr 捕获、异常清理和幂等终止。
- 性能优化必须先建立可重复基线；不得只凭单次耗时宣称优化有效。

## 8. Agent、知识与评测

- Prompt、Skill、知识条目和采集策略必须版本化；领域知识必须记录来源、适用条件和失效条件。
- 多轮分析复用不可变历史证据，通过新的 `analysisId` 和增量 Plan 继续，不覆盖上一轮数据。
- Evidence Sufficiency Evaluator 必须在生成确认性结论前检查证据覆盖、矛盾、截断和基线一致性。
- 每个 Agent 能力必须配套 Eval Case，至少覆盖成功、证据不足、工具失败和错误假设拒绝。
- Eval 结果与代码版本、模型/Prompt 版本、工具版本关联，避免不可比较的分数。

## 9. 修改纪律与质量门禁

- 只修改当前需求范围内的文件；保留用户已有变更，不得顺手重写无关模块。
- 禁止提交生成目录、真实敏感输入和大型原始 Trace；生产配置、测试及命令实现不得写死本机绝对路径。
  文档记录已验证的本地仓库拓扑时必须明确标注为示例，并同时提供可配置方式。
- 提交前至少执行受影响模块测试；跨模块契约变更执行根项目 `mvn test`。
- 交付前检查：设计已批准或自审、测试通过、文档同步、Schema 兼容、证据可追溯、性能预算未突破。
- 不得以“编译通过”代替行为验证；无法执行的检查必须在交付说明中明确列出原因和风险。
- 当前阶段不得为填充空模块而创建推测性 API。

## 10. 必读文档

- `docs/development/development-rules.md`
- `docs/architecture/README.md`
- `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- `docs/architecture/tool-validation-baseline.md`
- `docs/plans/algorithm-debug-agent-development-plan.md`
