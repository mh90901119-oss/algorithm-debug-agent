# 证据约束 MCP 子 Agent 实施基线

- 冻结日期：2026-09-28
- 基线提交：`aed3446`
- 权威设计：`docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md` 0.4
- 权威决策：`docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`
- 唯一实施计划：`docs/superpowers/plans/2026-09-28-evidence-constrained-portable-mcp-subagent.md`

## 1. 基线结论

设计 0.4、ADR-018 和替代实施计划已由用户批准，可以在当前根仓库按测试先行方式实施。旧的 0.3 实施计划已经
删除，不得从旧计划抽取零散 Task 继续实现。`.worktrees` 仅可作为只读历史参考；本轮实现不得从其中复制未验证
代码或在那里落地变更。

以下边界被冻结：

- Java Core 是采集、解析、校验、归档、状态投影和门禁的唯一确定性实现。
- MCP Server 不调用模型、不保存模型凭据、不实现第二套 Agent Loop。
- 知识 Markdown 是可选提示输入；没有知识文件也必须完成调查闭环，知识不能注册为 Evidence 或绕过门禁。
- `docs/sharing/` 是用户未跟踪资产，不修改、不暂存、不提交。
- 当前根仓库之外声称存在的 5 个工具没有名称、Schema、实现和测试，因此不在本阶段范围内。

## 2. 构建与依赖基线

| 项目 | 冻结值 | 验证方式 |
|---|---|---|
| Java 语言级别 | 21 | 根 `pom.xml` 的 `maven.compiler.release` |
| 实际 Agent Java | Temurin 21.0.11 | `java -version`、`mvn -v` |
| Maven | 3.9.16 | `mvn -v` |
| JUnit | 5.10.3 | 根 `pom.xml` |
| Jackson | 2.17.2 | 根 `pom.xml`；MCP 必须使用 Jackson 2 适配器 |
| Node.js | 24.18.0 | `node --version`，仅用于 Adapter/Eval/安装测试 |
| MCP Core | `io.modelcontextprotocol.sdk:mcp-core:2.0.1` | 在线和 `-o` 离线 `dependency:get` 均成功 |
| MCP Jackson 2 | `io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1` | 在线和 `-o` 离线 `dependency:get` 均成功 |
| MCP SDK 许可证 | MIT | 两个 2.0.1 POM 的 `<licenses>` |

验证命令：

```powershell
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-core:2.0.1"
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1"
mvn -o -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-core:2.0.1"
mvn -o -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1"
mvn -Pcodepath-launcher test
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

2026-09-28 结果：两个依赖坐标均可解析；20 个 Reactor Project 全部 `SUCCESS`；Node 71/71 通过。Java 测试仍输出
现有 SLF4J NOP provider 和 Byte Buddy 动态 Agent 加载预警，它们不是本次失败，但必须在最终打包/Java 升级门禁中
重新验证，不得静默忽略。

基线运行同时发现并修正了文档测试自身的两项边界错误：它不应扫描 Git 忽略的 `.superpowers` 执行临时目录，
也不应把普通中文词“公司”作为全仓陈旧内容标记。具体陈旧路径、旧数量和乱码规则继续保留。

## 3. 当前与目标 Tool Catalog

当前 OpenCode 迁移基线恰好包含以下 13 个已验证动作：

```text
analysis_begin
case_inspect
algorithm_input_capture
case_audit
gantt_inspect
run_test
static_analyze
codepath_plan_create
codepath_collect
jdwp_plan_create
jdwp_collect
artifact_read
evidence_query
```

第一阶段 Canonical Catalog 在此基础上增加：

```text
source_query
investigation_update
analysis_status
analysis_finalize
```

因此实施基线为 17 个 Action，但生产代码不得把 `17` 写成协议常量；完整性必须由
`EnumSet.allOf(AnalysisActionType.class)`、Catalog key set 和 `tools/list` 互相校验。外部 5 个未知工具只有在其真实契约、
实现来源和回归测试进入当前仓库后才能新增。

## 4. 固定查询与观测语义

`source_query` 第一版只允许六种 mode：

```text
METHOD
CALLERS
CALLEES
REACHABLE_PATH
SOURCE_WINDOW
SEARCH_SYMBOL
```

`ObservationPredicate` 第一版只允许七种 operator：

```text
METHOD_OBSERVED
RECORD_EXISTS
VALUE_EQUALS
VALUE_CHANGED
COUNT_COMPARE
PATH_CONTAINS
FAILURE_FINGERPRINT_MATCHES
```

不得用任意脚本、表达式语言或自由文本 expected observation 扩展这两个集合。新增能力必须有版本化契约、确定性
实现和 Eval 缺口证据。

## 5. 当前 Schema 基线

`SchemaVersions` 当前关键值如下；新实现只能追加新版本，不能原地改变历史文件语义：

| 契约 | 当前版本 |
|---|---:|
| Tool Response | 2.0 |
| Case Manifest / Analysis Request | 2.0 / 2.0 |
| Method Catalog | 3.0 |
| CodePath Plan | 6.0 |
| JDWP Plan | 5.0 |
| Collection Baseline Check | 2.0 |
| Method Path Summary | 5.0 |
| JDWP Snapshot Summary | 4.0 |
| Collection Validation | 2.0 |
| Evidence Bundle / Sufficiency | 2.0 / 2.0 |

目标版本为 CodePath Plan 7.0、JDWP Plan 6.0 和 Collection Execution Summary 3.0。旧 CodePath v6/JDWP v5
只读兼容，必须投影为 `LEGACY_UNSTRUCTURED`，不能自动满足结构化调查义务。

## 6. Qwen CLI 真实宿主契约

本机已验证 Qwen CLI `0.20.0`，可执行入口由 PATH 解析，不得在生产配置中写死本机绝对路径。用户级配置位于
`%USERPROFILE%/.qwen/settings.json`；Extension 安装目录位于 `%USERPROFILE%/.qwen/extensions/<name>/`。

Qwen Extension 的真实契约为：

- 根文件 `qwen-extension.json`。
- `mcpServers.<name>` 声明 `command`、`args` 和 `cwd`；路径可以使用 `${extensionPath}`、`${workspacePath}` 和
  `${/}` 占位符。
- 自定义子 Agent 位于 `agents/*.md`，使用 YAML front matter 声明 `name`、`description`、`tools`、`modelConfig`
  等宿主字段，正文承载子 Agent 指令。
- `qwen extensions install|list|uninstall` 管理 Extension；`qwen mcp add|list|remove` 管理直接 MCP 注册。

当前已安装的旧实验 Extension 指向 `.worktrees/qwen-mcp-gateway` 且处于禁用状态，只作为迁移现状，不是本轮代码
来源。Task 18 必须从当前根仓库 Canonical Agent Definition 生成新的薄 Adapter，并验证安装、检查、重装和卸载。

## 7. DeepSeek Harness 门禁

当前 PATH、`%USERPROFILE%/.deepseek`、`%APPDATA%/deepseek` 和 `%LOCALAPPDATA%/deepseek` 均未发现可执行文件或配置；
仓库也没有用户提供的真实 Harness 协议。因此当前无法证明它的 MCP 注册、子 Agent Profile 或安装布局。

这不阻塞 Core、Coordinator、MCP Server 和 Qwen Adapter 的实现，但会阻塞 Task 19 和整体可移植性完成声明。到达
Task 19 时必须取得真实 executable/version/config/profile/stdio MCP 契约并运行真实 smoke；禁止按名称猜测配置，
禁止用自造 fixture 宣称第二宿主兼容。

## 8. 兼容与资产边界

- `config/agent-settings.json`、`schemas/tool/tool-response-v2.schema.json`、`CliArguments` 和现有 OpenCode 资产在迁移
  门禁完成前保留。
- 新 MCP 配置只由 `config/mcp-agent-settings.json` 消费，不向旧配置混入宿主专属字段。
- 所有新 Case、Operation、Investigation、Source Query 和 Conclusion 产物追加保存；不迁移或覆盖历史 Artifact。
- 本文记录的宿主路径是 2026-09-28 本机验证示例；实现必须通过环境、PATH、宿主变量或安装参数解析，不得复制为
  固定开发机路径。
