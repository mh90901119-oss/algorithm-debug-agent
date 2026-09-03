# JDWP 精确投影实施计划

## 目标

把 JDWP 从根对象递归快照改成 Plan 驱动的精确值路径读取，支持最多四个 AND 条件，并让每个请求路径都有可供 LLM 判断的
确定性结果。保持单线程 Collector、同步 JSONL、现有进程监督和预算边界。

## 任务

1. 先修改 Contracts、Schema 和相关测试，验证旧 `localNames/fieldPaths` 契约不能满足新测试。
2. 在 JDWP Core 实现共享的顶层栈帧值路径解析和标量投影，删除递归对象 Snapshotter。
3. 在 Batch Collector 使用同一解析器完成多条件 AND 判断和精确投影，保留分离命中计数。
4. 将 Raw Trace 升级为精确 projections，重写 Normalizer 的确定性投影校验，删除对象树 Flattener。
5. 更新 Plan Compiler、Collector DTO、OpenCode Tool Schema/Adapter、Agent 和 Skill。
6. 更新 DFX 聚合日志，只记录 ID、预算和计数，不记录运行时值。
7. 运行受影响模块测试、根 Reactor、Node 契约测试、构建、loopback、安装生命周期和真实 OpenCode Smoke。
8. 审计每个动态 Case 的 Plan、Raw、Summary、Validation、Evidence、Interaction 和日志，确认无空目录和无用途文件。

## 非目标

- 不支持 Getter、方法调用、Map Key、List 遍历、数组下标、脚本表达式、OR/NOT。
- 不增加线程、异步写盘、队列、文件锁或第三方依赖。
- 不编码 wafer、job、chamber、Gantt 或调度策略语义。
- 不修改 CodePath 采集实现、Case 模型、Maven/JUnit Harness 或目标算法生产源码。
