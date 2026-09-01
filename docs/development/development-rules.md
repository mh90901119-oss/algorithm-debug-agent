# Algorithm Debug Agent 开发规则

本文件展开仓库根 [AGENTS.md](../../AGENTS.md) 的强制规则。发生冲突时以 `AGENTS.md` 为准。

## 1. 产品边界

- Agent 只定位离线算法 UT，不接管在线设备或生产决策。
- 默认不修改目标算法生产源码添加 Trace；使用外部 Runner、静态分析、CodePath 和 JDWP。
- LLM 负责规划与解释；代码负责可重复的解析、执行、采集、Hash、校验和归档。
- 报告必须区分 `CONFIRMED_FACT`、`VALIDATOR_CONCLUSION`、`SOURCE_INFERENCE`、`LLM_HYPOTHESIS` 和 `MISSING_EVIDENCE`。
- 成功 Gantt 不做内容 SHA 门禁；失败 UT 只用结构化失败指纹比较同类失败。

## 2. 设计与变更

- 新功能、跨模块、Schema/CLI/SPI、性能和安全变更先使用 [实现设计模板](../designs/implementation-design-template.md)。
- 改变不可逆架构边界时新增或更新 ADR。
- 实现中发现设计不成立时，先更新设计再继续代码。
- 流程、状态和时序图使用 Mermaid，并配文字解释箭头和边界。
- 不创建用于“占位”的模块、接口、目录或空文件。

## 3. 测试

- 行为和缺陷遵循 Red-Green-Refactor；回归测试必须能复现原问题。
- 单元测试不得依赖网络、真实时间、随机顺序或开发机绝对路径。
- 时间、ID、文件系统、进程和 Collector 使用可替换边界。
- 验证顺序：单元测试、契约/Schema、模块集成、关键 E2E、性能与 Eval。
- 不得删除失败测试、削弱断言或修改无关 golden 数据来换取通过。

## 4. Java 与依赖

- 使用 Java 21、Maven、JUnit 5。
- 公共模型优先不可变；错误结构化并保留 cause。
- `ada-contracts` 不依赖实现；Adapter 不反向依赖 `ada-core`；Collector 不包含调度语义。
- 跨模块依赖稳定 SPI/契约，不泄漏内部实现类型。
- 新依赖需记录用途、许可证、锁定版本和轻量替代方案。

## 5. 代码与文档

- 标识符、Schema 和协议字段使用英文；团队说明、Javadoc 和必要注释使用中文。
- 公共 API、SPI、核心模型和复杂算法写职责、边界、参数、返回值和异常。
- 注释解释原因和陷阱，不复述代码；禁止无上下文 TODO、注释掉代码和调试输出。
- 行为、命令、Schema 或产物变化时同步 README、使用文档和示例。
- 只保留当前文档；被当前设计和测试吸收的阶段性草稿应删除。

## 6. 产物与兼容

- Case、Plan、Manifest、Raw、Derived、Evidence 和 Report 使用版本化契约。
- Schema 默认向后兼容；破坏性变更升级主版本并提供迁移与兼容测试。
- JSON/JSONL 稳定、有界、可流式，并保留 provenance。
- Raw Trace 只读；Normalizer 只生成派生文件。
- 写入使用临时文件和原子提交；失败运行保留 manifest、退出码和截断原因。
- 日志和报告不得泄漏凭据、敏感路径或未脱敏生产数据。

## 7. 性能和外部进程

- 采集设计明确事件数、命中数、对象深度、字节、耗时和队列预算。
- 默认使用 allowlist、投影、topN、采样、流式写盘和有界队列。
- 测试 JVM、Launcher 和 Collector 必须有超时、退出码、stdout/stderr、异常清理和幂等终止。
- 性能结论需要可重复基线，不能基于单次耗时。

## 8. Agent 与 Eval

- Skill、Prompt、知识和采集策略版本化；知识记录来源和适用条件。
- 多轮复用不可变证据并创建新 `analysisId` 和增量 Plan。
- 确认性结论前执行 Evidence Sufficiency 检查。
- 每项能力至少 Eval 成功、证据不足、工具失败和错误假设拒绝。
- Eval 记录代码、模型/Prompt 和工具版本，禁止比较不可比结果。

## 9. 交付检查

1. 设计和 ADR 与代码一致。
2. 受影响模块测试通过；跨模块变更执行根测试。
3. 文档、Schema 和安装资产同步。
4. Case 产物可追溯且无空目录、未注册文件或覆盖历史。
5. 性能预算和外部进程清理验证通过。
6. 无法执行的检查在最终审计中记录原因和风险。
