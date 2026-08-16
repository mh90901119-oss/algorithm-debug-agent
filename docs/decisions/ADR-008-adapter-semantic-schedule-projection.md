# ADR-008：Adapter 提供语义调度结果投影，通用模块统一 Hash 与 Diff

- 状态：Proposed
- 日期：2026-08-17

## 背景

Algorithm Debug Agent 已能通过目标算法 Adapter 解析一次 UT 产生的 Gantt，并由 Adapter 自己计算语义
Hash。当前唯一实现是 Wafer Demo，因此类型化快照和 Hash 代码包含 `waferId`、`jobId`、`Chamber`
资源等字段。下一阶段需要建立 Baseline、说明两次结果的最小结构化差异，并支持未来字段更少或更多的
目标算法。

如果通用 `gantt-analysis` 直接理解 Wafer 字段，它会被 Reference Demo 绑定；如果每个 Adapter 分别
实现排序、Hash、Diff 和预算控制，又会产生重复逻辑，并可能出现“Hash 判定相同但 Diff 显示变化”的
矛盾。直接比较原始 JSON 也无法排除输出时间、运行 ID、绝对路径、说明文字和数组顺序等噪声。

## 决策

1. 每个 `TargetProjectAdapter` 将自己解析出的类型化结果映射为版本化、不可变、有界的
   `ScheduleResultProjection`；Adapter 负责选择哪些目标字段属于调度语义，哪些只是输出噪声。
2. 通用投影只表达稳定条目 Key、开始/结束时间、泳道 Key、结果级语义属性和条目级语义属性；它不
   固定 `wafer`、`job`、`chamber`、`recipe` 等业务字段。
3. 扩展语义属性仅允许有界的类型化标量，不允许嵌套任意对象图。字段缺失时省略，字段更多时由
   Adapter 显式加入；原始完整结果继续作为只读 Artifact 保存。
4. `gantt-analysis` 只依赖稳定 Contracts，对同一投影执行唯一规范化、语义 Hash 和最小结构化 Diff。
   Hash 与 Diff 必须使用完全相同的字段、排序和标量规范化规则。
5. Wafer Demo Adapter 只负责 `WaferScheduleSnapshot -> ScheduleResultProjection` 映射。通用模块的生产
   代码不得出现 Wafer 专属规则或根据扩展属性名称推断业务正确性。
6. 资源冲突、SERIAL/PARALLEL、防超车、候选评分和等待原因属于后续 Validator/Evidence/LLM 范围，
   不进入本次通用 Diff。
7. 没有稳定条目身份、投影语义版本不兼容或投影超出硬预算时，确定性返回 `INCOMPARABLE` 或结构化
   Agent 诊断，不猜测条目对应关系，也不对截断投影计算可冒充完整结果的 Hash。
8. 当前未发布的 `SemanticHashStrategy<T>` SPI 由 `ScheduleResultProjector<T>` 取代；Harness 只负责
   运行窗口捕获和原始文件 Hash，语义分析从 Harness 移到 `gantt-analysis`。

## 影响

- 新目标算法只需提供 Parser 和 Projector，不需要重复实现通用 Hash/Diff；
- Adapter 仍必须理解目标结果格式和最小业务字段语义，这是隔离业务差异的边界，不是通用业务规则
  引擎；
- `ada-contracts` 新增版本化投影、Diff、复现参考和比较记录，`adapter-sdk` 只暴露投影 SPI；
- `gantt-analysis` 不依赖具体 Adapter，`ada-core` 负责组合 Adapter、分析模块和 Case Repository；
- 已归档原始 Gantt 不迁移；旧 Run 的 `comparisonOutcome=NOT_COMPARED` 保持可读，新 Run 追加投影和
  比较 Artifact；
- SPI 变更发生在 `0.1.0-SNAPSHOT` 未发布阶段，仓库内唯一 Wafer Adapter 与契约测试同步迁移。

## 被否决方案

- **通用模块直接使用 Wafer DTO**：实现快，但其他算法必须伪造 Wafer 字段，违反 Adapter 隔离边界；
- **每个 Adapter 自己实现 Hash 与 Diff**：最灵活，但重复确定性逻辑，难以统一预算、审计和一致性；
- **直接对原始 JSON 做 JSON Patch**：无需投影，但噪声字段和数组顺序会造成大量假变化；
- **将任意 JSON 对象放入扩展属性**：看似通用，实际重新引入无界对象、非确定序列化和 LLM 上下文
  膨胀；
- **现在实现完整业务 Gantt 规则引擎**：缺少 CodePath、JDWP 和 Evidence 支撑，容易把现象误判为
  根因，超出当前里程碑。
