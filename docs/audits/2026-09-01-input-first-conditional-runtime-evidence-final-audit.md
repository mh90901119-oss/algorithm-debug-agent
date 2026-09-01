# 输入优先与条件化运行时证据最终审计

- 日期：2026-09-01
- 当前状态：阶段 6 已完成；阶段 7 自动化、构建和基础运行环境审计已通过；真实 OpenCode 10-Case E2E 与逐 Case Workspace/日志审计待继续。

## 已审计变更

- 算法输入支持 `input.json` 与 `input_.json`，首次按原名复制到 Case，后续 Analysis 复用并校验。
- 普通成功 Run 按原名归档 Gantt；CodePath/JDWP Collection 不复制 Gantt。
- CodePath/JDWP Plan 强制记录问题、假设、预期观察和 Evidence 谱系。
- JDWP 支持通用栈帧值路径条件，分离观察、匹配、捕获计数和预算。
- Eval Harness 增加跨实体因果 Case，并确定性检查 Plan 意图、谱系和条件化 JDWP。
- Workspace 初始化删除未使用的 config/system/knowledge/cache/temp 目录 API。
- Installer Check 补充算法输入 Tool 能力发现；JDWP loopback 补充条件与三类命中计数检查。
- 历史阶段性设计、审计、实验和参考文档删除；当前文档统一为现实现路径和契约。

## 已通过验证

| 验证 | 结果 |
| --- | --- |
| `node --test agent-evals/test/*.test.mjs` | 12 项通过 |
| OpenCode Adapter 与 Eval（不含最终文档链接测试） | 54 项通过 |
| `mvn -pl case-management -am test` | Contracts 88 项、Case Management 97 项通过；1 项环境条件性跳过 |
| `mvn -Pcodepath-launcher test` | 20 个 Reactor project 全部成功 |
| `scripts/build-agent.ps1` | 成功，Java CLI、CodePath Launcher、JDWP Collector 完成构建 |
| `scripts/verify-opencode-installer.ps1` | 临时目录安装、重复安装、Check、卸载、幂等卸载、重装全部通过 |
| `scripts/verify-ada-launcher.ps1` | 在本地目标算法 Demo 模块通过 |
| `scripts/verify-jdwp-loopback.ps1` | loopback attach、条件快照与分离命中计数通过 |

构建只有 Maven Shade 重复许可证/`module-info` 警告、SLF4J 测试环境 NOP 警告和 JDK 动态 Agent 未来限制警告；本次无测试失败。这些警告没有进入 Agent Tool stdout 协议。

## 尚未完成

1. 使用本轮安装副本运行真实 OpenCode `cross-wafer-causal` Case。
2. 运行完整 10-Case Smoke Suite。
3. 对每个 Case 检查预期文件、可选文件、空目录、`interaction.jsonl`、Java 日志和 `case_audit`。
4. 将真实 E2E 结果、Case ID、Evidence 链和剩余风险追加到本审计后，才可把阶段 7 标记完成。

因此本文件当前不宣称阶段 7 完成，也不把 Java/Node 单元测试替代真实 OpenCode E2E。
