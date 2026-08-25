# Architecture documentation index

本目录区分“当前实现”“已验证事实”和“历史设计”，避免把旧目标当成现有能力。

## 当前权威文档

1. [当前能力与边界](../current-capabilities.md)：已经实现的产品能力和限制。
2. [工作流与产物](../algorithm-debug-workflow-and-artifacts.md)：OpenCode、LLM、Skill、Tool、Java Agent 与 Workspace 的实际交互。
3. [当前模块详细设计](algorithm-debug-agent-module-detailed-design-v1.md)：当前模块职责、运行时流程和可靠性边界。
4. [工具验证基线](tool-validation-baseline.md)：CodePath、JDWP、安装器、测试和真实 OpenCode E2E 的验证事实。
5. [最终实施审计](../audits/agent-runtime-simplification-final-audit.md)：本轮精简结果、九个 E2E 和每种 Case 文件的审计结论。
6. [ADR-008](../decisions/ADR-008-json-content-fingerprint-baseline.md)：Artifact 完整性与失败复现基线的当前决策。
7. [ADR-014](../decisions/ADR-014-agent-owned-jdwp-collector.md)：JDWP Collector 源码归属。

## 当前实施设计

- [Agent 运行时精简、完整审计与端到端验收](../designs/2026-08-25-agent-runtime-simplification-and-audit-design.md)
- [Agent-owned JDWP Collector](../designs/2026-08-23-agent-owned-jdwp-collector-design.md)
- [Explicit Context 与精确 CodePath](../decisions/ADR-010-explicit-context-and-exact-codepath.md)
- [Core 精简设计](../designs/2026-08-19-core-simplification-design.md)

## 历史和目标文档

- [完整架构和开发计划](algorithm-debug-agent-complete-design.md)
- [架构文档审计](architecture-document-audit-2026-08-10.md)
- [JDWP 上游原型重构与用法](jdwp-mcp-collector-refactoring-design-and-usage.md)
- [JDWP P0 性能加固目标](jdwp-collector-p0-performance-hardening-design.md)

历史文档只说明决策演进或未来目标。发生冲突时，按“当前能力 -> 工作流与产物 -> 当前模块详细设计
-> 工具验证基线 -> 当前 ADR”的顺序解释；不得从历史设计推断功能已经实现。
