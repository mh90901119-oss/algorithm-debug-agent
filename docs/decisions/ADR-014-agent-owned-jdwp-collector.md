# ADR-014：Agent 仓库统一维护最小 JDWP Collector

- 状态：Accepted
- 日期：2026-08-23

## 背景

Agent 原先运行仓库内归档的 Collector JAR，但源码维护在独立 `mcp-jdwp-java` 仓库。跨电脑
使用虽不需要手工路径配置，修复 descriptor、重复断点和值类型问题时仍需跨仓同步源码、版本和
二进制，容易出现 Agent 契约与 JAR 不一致。

## 决策

将 Agent 实际需要的 JDI Core 和 Batch Collector 源码迁入 `jdwp-collector-core` 与
`tools/jdwp-batch-collector`。保留原 MIT 许可和 `one.edee.mcp.jdwp` 包名，不迁入 MCP Server、
Spring 或 sandbox。

运行入口仍是 `jdwp-collector-adapter` 启动仓库内置 JAR。兼容性使用 Collector Manifest 的
版本和能力握手，不使用 JAR SHA。CodePathTracer 继续作为独立第三方依赖。

## 后果

- 一次 clone 即具备 JDWP 源码、构建和运行产物，修复可以与 Agent 契约原子演进。
- Agent Reactor 增加两个小型 Maven 模块和一份发布 JAR。
- 上游 `mcp-jdwp-java` 后续更新不会自动进入 Agent，必须按需求选择性移植并测试。
- Raw/Summary 升级为 v2，Normalizer 必须继续读取 v1 历史证据。

## 被否决方案

- Git submodule：仍有初始化、版本和离线可用性成本。
- 继续只归档外部 JAR：源码与运行契约容易漂移。
- 迁入整个 MCP 仓库：引入无关框架和交互能力，超过离线目标 UT 调试需求。
