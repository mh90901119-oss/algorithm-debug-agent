# 架构索引

## 当前文档

1. [当前能力与边界](../current-capabilities.md)
2. [工作流与产物](../algorithm-debug-workflow-and-artifacts.md)
3. [模块详细设计](algorithm-debug-agent-module-detailed-design-v1.md)
4. [工具验证基线](tool-validation-baseline.md)
5. [当前实现设计](../designs/2026-09-01-input-first-causal-evidence-and-conditional-jdwp-design.md)
6. [最终实施审计](../audits/2026-09-01-input-first-conditional-runtime-evidence-final-audit.md)
7. [可移植 MCP 子 Agent 与证据约束调查运行时设计 2.2](../designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md)
8. [证据约束 MCP 实施基线](../development/evidence-constrained-mcp-implementation-baseline.md)
9. [可移植 Agent 边界简化设计](../designs/2026-09-29-portable-agent-boundary-simplification-design.md)
10. [可移植 Agent 边界简化最终审计](../audits/2026-09-29-portable-agent-boundary-simplification-audit.md)

## 核心决策

- [ADR-006：Case 是追加式分析档案](../decisions/ADR-006-case-as-analysis-dossier.md)
- [ADR-007：OpenCode 通过 CLI Adapter 集成](../decisions/ADR-007-opencode-adapter-via-cli.md)
- [ADR-008：失败指纹取代 Gantt SHA 门禁](../decisions/ADR-008-json-content-fingerprint-baseline.md)
- [ADR-009：先保留通用运行时证据](../decisions/ADR-009-generic-runtime-evidence-before-domain-mapping.md)
- [ADR-010：显式 Context 与精确 CodePath（Context 部分已被 ADR-015 取代）](../decisions/ADR-010-explicit-context-and-exact-codepath.md)
- [ADR-012：统一路径配置](../decisions/ADR-012-unified-agent-path-configuration.md)
- [ADR-013：Case 内 DFX 日志](../decisions/ADR-013-case-local-dfx-interaction-log.md)
- [ADR-014：Agent 自维护 JDWP Collector](../decisions/ADR-014-agent-owned-jdwp-collector.md)
- [ADR-015：删除 Context，运行时基线收敛到 Analysis](../decisions/ADR-015-remove-context-and-scope-runtime-baseline-to-analysis.md)
- [ADR-018：Java 原生 MCP 可移植子 Agent](../decisions/ADR-018-java-native-mcp-portable-subagent.md)

当前实现以设计 2.2、边界简化设计和 ADR-018 为准。若历史 ADR 的背景描述与当前代码不同，以其后续 ADR、已批准的当前设计和代码
为准；不得恢复已删除的阶段性设计文件或 0.3 实施计划。
