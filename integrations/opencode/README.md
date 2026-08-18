# OpenCode integration

这是当前唯一客户端适配层，调用链为：OpenCode 大模型 → canonical Skill → OpenCode Custom Tool →
`ada` CLI → Java Core。适配层只负责稳定的参数映射和进程边界，不复制事实、不判断算法业务语义。

## 当前资产

- `tools/algorithm-debug.ts`：10 个真实 Tool，覆盖 Case 创建/续接与检查、UT 单次运行、静态分析、
  CodePath/JDWP 计划和采集、Artifact 分段读取以及 Analysis 完成归档；
- `lib/tool-runtime.mjs`：每次调用先幂等初始化外部 Workspace、登记当前 Maven 模块，并从结构化响应
  取得 `projectId`。用户和大模型不需要填写 Workspace 或 `projectId`；
- `lib/ada-cli.mjs`：stdout/stderr 各以 1 MiB 为上限，只原样返回通过 ToolResponse 2.0 校验的
  stdout；默认总运行预算 15 分钟；启动、超时、超限或协议错误均转为结构化 Adapter 失败；
- `agents/algorithm-debug.md`、`commands/debug-case.md`：待一次性安装器登记和实测的 OpenCode 资产；
- 规范 Skill 位于 `skills/algorithm-debug/SKILL.md`。

## 路径与临时文件

本仓库内运行时默认调用仓库自己的 `bin/ada.cmd`；可用 `ADA_CLI` 显式覆盖启动器。外部 Case
Workspace 默认位于当前用户数据目录下的 `algorithm-debug-agent/workspace`，可用 `ADA_WORKSPACE`
覆盖。Workspace 不写入目标算法模块。

问题、CodePath/JDWP Plan 请求和 Analysis 结果通过系统临时目录中的 UTF-8 普通文件传给 Java CLI，
上限分别为 64 KiB、64 KiB 和 256 KiB；成功或失败后都清理。Artifact 每次最多读取 64 KiB，
只接受 Case 内已登记的 Artifact ID，不接受任意文件路径。

## 使用状态

Java 后端和 Tool 映射已经对齐，但本目录仍不是可直接复制使用的安装包。下一阶段需要实现并验证
OpenCode 1.18.15 的一次性 `install/check`：进入目标算法模块执行 `opencode` 后即可发现仓库拥有的
Skill、Agent、Command 和 Tools。当前不提供 MCP Server。

适配层不猜 Context：已有 Case 默认使用 `reuse`；只有模型根据用户明确说明确认目标算法源码、UT
或输入被有意修改时才使用 `new`。Gantt `CHANGED` 只是模型可分析的事实，不触发自动切换。
CodePath 和 JDWP 都必须先归档 Plan，再按当前证据缺口执行一次或多次 Collection。
