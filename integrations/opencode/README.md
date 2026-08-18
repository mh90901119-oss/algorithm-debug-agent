# OpenCode integration

这是当前唯一客户端适配层，调用链为 OpenCode Model → canonical Skill → OpenCode Custom Tool →
`ada` CLI → Java Core。该目录不复制或改写事实，也不包含算法业务语义。

- `tools/algorithm-debug.ts`：最终 Tool 契约源码；安装器还需把当前 `context.directory` 映射到已登记项目，
  注入外部 Workspace/`projectId`，并把尚未实现的高层命令映射到稳定 CLI 后才能登记使用。
- `lib/ada-cli.mjs`：stdout/stderr 各以 1 MiB 为上限读取；只原样返回通过 ToolResponse 2.0 校验的
  stdout；默认总运行预算 15 分钟；启动、超时、超限或协议错误返回结构化 Adapter 失败、终止 CLI
  且不回显原始日志。
- `agents/algorithm-debug.md`：待安装器登记和实测的显式 Debug Agent 资产。
- `commands/debug-case.md`：待安装器和模型端到端验证的 `/debug-case` 命令资产。

规范 Skill 位于 `skills/algorithm-debug/SKILL.md`。仓库现已提供可执行 CLI 的 Workspace init、Project
register、Doctor、Case/Run、静态分析以及 CodePath/JDWP Plan/Collection 后端命令；本目录仍不是可直接使用的安装包：
OpenCode 一次性安装器、锁定版本的外部目录发现验证和模型端到端 `/debug-case` 编排尚未实现。不能假设
任意外部目录会被 OpenCode 自动发现。最终体验仍是进入算法仓库后直接执行 `opencode` 并提问。当前不提供
Agent MCP Server。

适配层不猜 Context：已有 Case 默认传 `--context-mode reuse`；只有模型根据用户明确说明确认目标算法源码、
UT 或输入被有意修改时才传 `--context-mode new`。Gantt `CHANGED` 只是提供给模型的事实，不触发自动切换。
CodePath 必须先从 Method Catalog 选择精确方法并归档 Plan，再按需执行一次或多次 Collection。
