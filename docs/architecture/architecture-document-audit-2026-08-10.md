# Algorithm Debug Agent 架构文档审计报告

- 日期：2026-08-10
- 范围：`docs/architecture`全部现有设计文档
- 审计触发：CodePathTracer与JDWP Batch Collector均完成真实复杂UT单点验证
- 不变目标：零算法源码侵入、证据驱动、易用、可迁移、适应大型复杂算法

## 1. 审计结论

总体架构方向正确，不需要推翻。需要修订的是文档状态和能力边界：过去多处把“建议重构/目标Schema”与“当前实现”写在同一语气中，容易让实施者误以为Plan投影、异步Writer、参数摘要、Source Anchor等已经可用。

本次新增`tool-validation-baseline.md`作为唯一事实基线；总体设计负责产品和架构，模块详细设计负责Agent实现，JDWP重构文档负责工具MVP，P0文档负责尚未实现的性能加固。

## 2. 逐文档审计

### 2.1 `algorithm-debug-agent-complete-design.md`

定位：保留为产品目标、总体架构和端到端生命周期主入口。

发现：

- “推荐抽取共享JDWP Core”已经过时，第一阶段抽取已完成；
- CodePath参数/返回值摘要没有被当前原型验证；
- Path Plan示例未明确是目标契约；
- 开发Phase和Backlog没有区分工具单点完成与Agent集成未完成；
- 当前Demo基线缺少两次真实工具验证事实。

修订：升级到1.1；引入统一验证基线；更新实际JDWP模块；纠正CodePath能力；为CodePath/JDWP Phase增加状态；把Backlog改为Agent集成任务。

### 2.2 `algorithm-debug-agent-module-detailed-design-v1.md`

定位：保留为新Agent仓库的模块实施基线。

发现：

- 文档仍标记“尚未进入代码实施”，但仓库和20模块骨架已创建；
- 仍允许CodePath失败时生成Companion UT，与已验证外部Launcher和零修改目标冲突；
- JDWP Adapter章节缺少最新真实能力和缺口；
- 性能目标容易被误读为当前Collector能力；
- D1、D6、D10等决策仍写成建议。

修订：升级到1.1实施状态；禁止MVP生成采集UT；新增JDWP验证和当前缺口；明确P0目标/现状；更新Phase 0/4和已确认决策。

### 2.3 `jdwp-collector-p0-performance-hardening-design.md`

定位：保留为大型算法接入前的JDWP专项P0设计，不合并进Agent代码。

发现：

- 技术方向与最新验证一致；
- 需要明确最新复验没有证明P0已经实现；
- 需要把2.25MB全locals快照作为投影/白名单必要性的直接证据；
- Agent在P0前需要保守兼容策略。

修订：增加2026-08-10复验表、状态警告和P0前使用限制。Definition of Done保持未勾选。

### 2.4 `jdwp-mcp-collector-refactoring-design-and-usage.md`

定位：保留为工具仓库第一阶段重构说明和Collector手工使用手册。

发现：

- 旧验证路径不是当前复验路径；
- MCP全量测试备注属于早期记录，当前只重新运行Core/Collector测试；
- 缺少当前JAR/Trace/Gantt一致性信息；
- 对全locals快照的噪音描述不够直观。

修订：记录commit `1ef7d22`和本次产物；区分165个业务命中与167个总事件；明确MCP测试未在本轮重跑；补充2.25MB Trace中的JDK集合内部字段噪音。

## 3. 新增文档

### `tool-validation-baseline.md`

统一记录：

- 工具仓库、commit、JAR和观察到的Hash；
- 共同UT和Gantt Hash；
- CodePath 41,436事件验证；
- JDWP 165命中/167事件/2,246,165字节验证；
- 每项已证明能力和未实现能力；
- 大型算法和易用性对Agent实施顺序的约束。

## 4. 冻结后的术语

```text
DebugIntent        LLM可以提出的问题级采集意图
ResolvedPlan       静态分析把意图映射到Catalog/Source Anchor后的计划
CompiledPlan       Validator校验后Collector可执行的有限计划
Method Path        CodePath方法进入/退出事实
Raw JDWP Trace     Collector读取的JVM事实
Domain Trace       Normalizer从Raw事实确定性派生的领域事件
Evidence           带Provenance、可被报告引用的事实或确定性推导
```

禁止把Raw Trace称为Domain Trace，禁止把手工CSV视为Normalizer输出，禁止把设计Schema字段视为Collector已实现能力。

## 5. 实施影响

近期开发顺序不再从“继续证明工具能否工作”开始，而是：

1. `ada-contracts`和最小Schema；
2. Case/Baseline/Gantt Semantic Hash；
3. Static Source Anchor和Method Catalog；
4. CodePath Plan Adapter与流式预算；
5. JDWP Collector Adapter与P0能力锁；
6. Normalizer/Validator/Evidence；
7. OpenCode易用入口和多轮状态；
8. 大计划压力测试、发行包和Golden Evaluation。

## 6. 仍待产品方确认

- Agent仓库正式许可证；
- 最终Java group/package namespace；
- 大型公司算法的首个脱敏Fixture和性能规模；
- P0加固是先在JDWP仓库完成全部项，还是先实现Agent MVP所需的allowlist/projection/limits子集；
- Viewer第一版采用静态HTML还是本地服务。

