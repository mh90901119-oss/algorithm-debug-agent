# ADR-011：通用 Maven/JUnit Adapter 与项目级 JSON 结果配置

- 状态：Accepted
- 日期：2026-08-20

## 背景

现有 Wafer Demo Adapter 将固定 UT、输入文件和输出目录写入生产代码，导致其他 Maven/JUnit 项目无法直接使用 Agent。UT 本身已经是完整执行单元，输入定位不应成为 Agent 的运行前置条件。

## 决策

1. 使用无领域语义的 Maven/JUnit Adapter 接受任意合法 `Class#method` 目标。
2. 可选算法 JSON 结果目录保存在外部 Workspace 的 `ProjectRegistration.resultJsonDirectory`，值为相对 `moduleRoot` 的安全可移植路径。
3. Adapter 不再负责输入定位、结果目录定位或业务结果解析。
4. 第一阶段继续使用 `GANTT` Artifact、`raw/gantt.json`、`ganttOutcome` 和 normalized Gantt SHA 作为兼容命名。
5. Java 不实现失败原因分类器；大模型根据 UT 的实际证据分析具体问题。

## 影响

旧 `project.json` 缺少新字段时仍可读取。OpenCode 自动注册未传结果目录时保留已有配置。历史 Gantt 产物无需迁移；命名清理只有在真实消费者需要时另行设计。
