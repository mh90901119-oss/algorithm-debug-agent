# ADR-011：通用 Maven/JUnit 与 JSON 结果适配

- 状态：Accepted，已由 ADR-008 的失败指纹决策修订
- 日期：2026-08-20
- 更新：2026-09-01

## 背景

目标算法以 Maven/JUnit UT 作为最小复现入口，运行后可能在配置目录生成带时间戳文件名的 JSON Gantt。Agent 不能要求每个目标仓库实现专用 Java Adapter，也不能把本地 Demo 路径写入生产配置。

## 决策

1. 使用通用 Maven/JUnit Adapter 精确执行一个测试类或方法。
2. 目标模块来自 OpenCode 当前工作目录，路径配置来自 `config/agent-settings.json`，不在目标 POM 中注入 Agent 依赖。
3. `resultJsonDirectory` 支持绝对路径、相对目标模块路径和 `${runDate}` 日期变量。
4. 普通 Run 比较执行前后目录快照，只捕获本次新增或内容变化的 `.json`；归档保留源文件名。
5. CodePath/JDWP Collection 的重跑不捕获 Gantt，避免把采集扰动结果混入普通 Run 结果。
6. 成功 Gantt 不计算用于跨 Run 门禁的 normalized SHA；LLM 按用户问题分析每次独立结果。
7. 目标 UT 失败时保留结构化失败指纹，动态 Collection 只有 `MATCHED` 才确认同类失败复现。
8. 输入文件由独立输入捕获能力在 Run 前确定，不由 JSON 结果 Adapter 猜测。

## 结果

- 目标算法仓库无需依赖 Agent Java 模块。
- 安装时只需配置 JDK、Maven、Workspace 和算法结果目录。
- 时间戳文件名变化不会影响归档；原始文件可直接人工查看。
- Agent 不把成功调度结果变化误判为 Collector 故障。
- Artifact SHA 仍用于归档文件完整性，但不表示业务结果相同。
