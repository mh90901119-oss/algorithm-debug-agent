# ADR-007：OpenCode 通过仓库内 Skill 与 CLI 薄适配接入 Agent

- 状态：Accepted；CLI Implemented / Installer Pending
- 日期：2026-08-12

## 背景

Algorithm Debug Agent 当前以 OpenCode 作为大模型 Agent Runtime。用户的正常场景是在目标算法仓库中
直接启动 `opencode`，指定一个 Maven/JUnit UT 并提出 Gantt 或异常问题。Agent 的 Skill、OpenCode
Agent 定义、命令、Custom Tool、Java CLI、Schema 和配置模板必须由 Algorithm Debug Agent 仓库统一管理，
不能复制到每个目标仓库或形成多个可漂移的 Skill 副本。

OpenCode 必须经过一次发现和工具注册才能调用另一个目录中的 Agent。当前已有稳定方向是由 OpenCode
Custom Tool 调用 `ada` CLI；尚无第二种客户端的实际交付需求，因此引入 Algorithm Debug MCP
Server 会增加协议、打包和测试范围而没有当前收益。

## 决策

1. 当前阶段仅支持 OpenCode，不适配 Codex CLI、Qwen CLI 或其他 Agent Runtime；
2. 唯一 Skill 源码位于 `skills/algorithm-debug/SKILL.md`，OpenCode 专属内容位于
   `integrations/opencode`；
3. OpenCode Custom Tool 只校验参数、获取当前 directory/worktree、调用 `ada` CLI、解析并原样
   返回 ToolResponse，不实现 Maven、采集、异常解释、Case 持久化或业务判断；
4. `ada` CLI stdout 只输出有界 ToolResponse JSON，日志写 stderr 和 Artifact；每次 UT 执行返回
   `RunOutcomeSummary` 与原始 Artifact 引用；
5. 提供幂等的一次性 OpenCode 适配安装。它在 OpenCode 用户配置中登记 Agent 安装路径、外部 Skill
   来源和薄 Custom Tool，不把 Skill 正文复制到全局 Skill 目录；
6. 安装完成后，用户进入目标算法仓库直接运行 `opencode` 并提问。显式命令和 Agent 定义属于后续
   安装器需要登记并验证的入口，本 ADR 不把仓库内文件存在视为可用回退链路；
7. `ada opencode --project ...` 仅作为开发、自测或临时免安装入口，不是日常主流程；
8. 当前阶段不实现 Algorithm Debug MCP Server。外部 JDWP-MCP 的历史工具能力与本决策无关，不属于
   OpenCode Agent 接入链路；
9. 目标仓库不保存 Agent 产品资产，只保存可配置位置的 Case 运行证据。
10. OpenCode 的外部 Skill/Tool/Agent 发现与用户配置格式必须按锁定版本实测。当前仓库中的模板和
    `integrations/opencode` 目录不是“已自动发现”的证据；安装器可以部署只负责加载仓库资产的薄入口，
    但不得复制规范 Skill 或 Java 业务逻辑。

## 影响

- Agent 领域代码和契约不依赖 OpenCode 类型；OpenCode 变化被隔离在薄适配目录；
- OpenCode 用户配置只包含发现/加载引用，不成为 Case、Run、Analysis 或 Evidence 的事实源；
- 安装、升级、检查和卸载必须有离线、幂等和回滚测试；
- Custom Tool 必须验证 CLI ToolResponse Schema，且不得重写 `eventType`、ID、结果、比较状态或 Artifact 引用；
- Custom Tool 对 stdout/stderr 分别施加 1 MiB 上限并设置 15 分钟默认总预算；超时、超限、启动失败
  或协议错误不回显原始输出，并终止已启动的 CLI 进程；
- 后续出现第二种客户端的真实需求时，可以复用 CLI JSON 契约并单独评估 MCP，而不提前创建推测性模块。

## 实现状态

截至 2026-08-17，规范 Skill、OpenCode Agent/Command/Tool 源码、ToolResponse 2.0 校验、有界 I/O
单元测试和可执行 `ada` CLI 已经存在。CLI 当前提供 Workspace 初始化、Maven 模块登记和 Doctor；
Case/Run 命令随后已经实现，但 Tool 源码中的高层命令映射、Workspace/`projectId` 注入、安装/升级/
检查/卸载命令、真实 OpenCode 加载验证以及端到端 Debug Case 仍未实现，因此当前不能把目标体验表述为
已可使用，也不得直接登记该 Tool 源码。

## 被否决方案

- 每次使用 `ada opencode --project ...` 启动：零安装但日常操作复杂，不符合进入目标仓库直接使用
  `opencode` 的预期；
- 把 `.opencode`、Skill 和工具复制到每个目标仓库：污染目标项目并产生版本漂移；
- 把 Skill 正文安装到全局 Skill 目录：形成 Agent 仓库之外的第二份工作流源码；
- 当前直接实现 MCP Server：没有多客户端需求支撑，增加协议和测试面；
- 让 OpenCode Custom Tool 直接运行 Maven、解析 Gantt 或管理 Case：会复制 Java Core 的确定性逻辑。
