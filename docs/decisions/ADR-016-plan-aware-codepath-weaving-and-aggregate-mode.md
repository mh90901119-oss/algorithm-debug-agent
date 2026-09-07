# ADR-016：CodePath 采用计划感知织入与双采集模式

状态：接受  
日期：2026-09-06

## 背景

第三方 CodePath Agent 先对广泛方法织入，再由 Agent Plan 在回调阶段过滤。大型目标算法中，未选择调用数量远大于保留调用，造成运行时间超限；TRACE 的前缀截断还会掩盖后段关键调用。

## 决策

1. Launcher 直接依赖锁定版本 Byte Buddy，仅对 Plan 中精确 `className + methodName + descriptor` 织入。
2. `TRACE` 保留逐调用证据；`AGGREGATE` 只保留有界计数，用于先发现再缩小。
3. Scope 条件和匹配序号窗口在投影及 JSON 构造前执行。
4. 继续复用现有 Normalizer、Artifact Registry、SHA 校验和 Evidence Query；不引入新的存储服务。
5. 深度和父子关系只声明为已选择方法视图。

## 影响

- 删除 Launcher 对 `code-path-tracer` Snapshot 与 Kotlin 标准库的依赖。
- 新增 Byte Buddy 与 Byte Buddy Agent 1.15.11，Apache-2.0 许可证；替代方案是修改第三方库，但会继续保留宽织入边界。
- AGGREGATE 不能替代 TRACE 的顺序、相关性与单次调用事实。
- 不支持多线程目标 UT；若多个线程命中已选择方法，返回结构化工具失败，避免伪造单线程调用树。

## 被否决方案

- 默认把预算提高到 100 MiB：只延后截断并增加模型检索成本。
- 单独生成直方图 Artifact：与现有 Summary/COUNT 重复。
- 异步生产者消费者写盘：增加并发故障面，未解决宽织入根因。
- 自动猜测业务字段：不通用且会把模型推断固化进 Collector。

