# ADR-018：采用 Java 原生 MCP Server 交付可移植 Algorithm Debug 子 Agent

- 状态：Accepted
- 日期：2026-09-25
- 批准日期：2026-09-28
- 决策范围：模型入口、Agent 边界、MCP Server、Coordinator、宿主适配器
- 关联设计：[可移植 MCP 子 Agent 与确定性 Coordinator 可实施详细设计](../designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md)
- 取代范围：实施完成并通过迁移门禁后，取代 ADR-007 的 OpenCode 专用集成边界，并取代 ADR-017 中 Qwen 专用 Gateway、双 Runtime 产品形态和 OpenCode 兼容基线；保留“Java 确定性后端、Adapter 薄、领域规则不进入宿主 Core”的原则

## 背景

当前 Agent 通过 OpenCode Agent、Skill、13 个 Custom Tool、JS Runtime 和 Java CLI 工作。另有 Qwen MCP
设计及独立 worktree 实验，但当前根仓库的正式运行边界仍然绑定具体宿主。目标产品需要作为多种编程工具的
子 Agent：Qwen CLI、DeepSeek Harness 和后续宿主应复用同一套算法分析能力、流程门禁、证据语义和评测，
而不是各自维护工具定义和业务逻辑。

MCP 可以统一宿主与能力服务之间的工具、资源和 Prompt 协议，但 MCP Server 不等于模型 Agent。子 Agent 的
模型会话仍由宿主创建。仓库必须同时提供宿主无关 Agent Definition 和 MCP Server，才能在不复制领域逻辑的
前提下适配不同宿主。

当前每次工具调用都经过 JS Runtime 启动 Java CLI。该路径适合作为早期 Adapter，但长期保留会形成 TypeScript
Schema、CLI 参数和 Java DTO 三份契约，并使 Coordinator、并发和错误语义跨进程分散。

## 决策

1. 仓库交付一个宿主无关的 Algorithm Debug Agent Package，由 Canonical Agent Definition、Java 原生 MCP
   Server、Analysis Coordinator、现有 Java 分析内核、Case Workspace 和 Agent Eval 组成。
2. 宿主创建和运行模型子 Agent；MCP Server 不持有模型凭据、不调用模型、不实现 Agent Loop。
3. 新增 `algorithm-debug-runtime` 作为 CLI 与 MCP 共用的唯一生产组合根；新增
   `algorithm-debug-mcp-server` Maven 模块，第一版只支持本地 stdio 传输。Runtime 依赖具体 Adapter/Collector，
   入口模块不复制该装配逻辑。
4. MCP Server 使用官方 MCP Java SDK，初始锁定 2.0.1，并使用 `mcp-core` 与
   `mcp-json-jackson2`；根 Jackson 2 BOM 与该 SDK 的编译基线统一为 2.21.1，不引入 Spring，不迁移到 Jackson 3。
   依赖必须通过离线镜像、许可证、NOTICE 和 SBOM
   预检后才能进入生产实现。
5. MCP Tool Handler 直接调用 `ada-core`，正常模型调用不再启动 `bin/ada.cmd`。CLI 保留为人工、CI、诊断和
   迁移入口，并与 MCP Server 复用同一 Coordinator。
6. 所有模型可见动作必须经 `AnalysisCoordinator`。Coordinator 从追加式 Case 产物重建状态，执行动作授权、
   文件锁、幂等、后置条件、证据义务和结论门禁。大模型不需要、也不能通过一个可选 Tool 绕过 Coordinator。
7. 保留粒度明确的多个 MCP Tool，不创建包含所有动作和联合参数的万能工具。所有 Tool 从唯一 Canonical
   Catalog 注册，宿主适配器不得复制名称、Schema 或业务规则。
8. 当前根仓库的 13 个工具作为迁移基线，并新增 `source_query`、`investigation_update`、`analysis_status`、
   `analysis_finalize`，形成 17 个基线 Action。生产代码从 Action/Catalog 集合计算完整性，不把数量写成协议常量。
   公司环境额外 5 个工具必须在提供真实契约、实现来源和测试后导入，不得推测创建。
9. 新增追加式 Investigation Runtime，保存唯一 Problem Frame、竞争假设、证据缺口、冻结 Predicate、三值
   Evaluation、支持与反证；新增有界 `source_query`，让源码理解不依赖宿主私有读文件工具。
10. 新增 Canonical Agent Definition，版本化记录角色、输入、所需 MCP 能力、Prompt、权限和完成契约。关键
   安全、证据和流程规则由代码执行；Prompt 只指导模型的业务推理和工具选择。
    知识 Markdown 是可选提示，不进入 Evidence、Predicate Evaluation 或 ConclusionGate。
11. 每个宿主只提供薄 Adapter：注册同一个 MCP Server、创建子 Agent、加载 Canonical Agent Definition、映射
    权限并完成兼容检查。先实现 Qwen CLI，再以第二个真实宿主证明可移植性。
12. 第一版不提供远程 Streamable HTTP、共享服务、多租户或 OAuth。出现真实需求后单独设计。
13. 所有实现只在当前根仓库进行；`.worktrees` 中的实验代码只能作为只读参考，不作为实施目录或直接覆盖来源。
14. 0.3 实施计划因缺失 17 Tool、Source Query、Investigation、Observation 和 CausalChain 已失效；只能执行
    2026-09-28 的替代计划，不允许在旧计划上叠加补丁。

## 影响

- 根项目新增共享 Runtime 与 MCP Server 两个 Maven 模块，并增加官方 MCP Java SDK 依赖。
- 模型正式入口从宿主专用 Custom Tool/CLI Adapter 转为标准 MCP Server。
- `ada-core` 新增协调层；Case Management 新增跨进程锁、操作日志和状态投影支持。
- `static-analysis` 新增有界 Source Query；`evidence-engine` 新增 Predicate、反证和因果链资格评估。
- MCP 使用新的协调结果契约；历史 Artifact 和 CLI `ToolResponse 2.0` 保持可读。
- `algorithm-debug-cli` 不再是模型正常调用链的一部分，但继续提供管理和诊断价值。
- Agent 的角色与 Prompt 从 OpenCode 专用文件迁移为 Canonical Agent Definition；宿主资产由其生成或引用。
- 需要增加 MCP 协议、并发、恢复、幂等、跨宿主和结果收敛评测。
- OpenCode 资产在新路径通过完整发布门禁后退出正式架构；此次决策不要求在第一批实现中立即删除。

## 被否决方案

### Node MCP Server 每次调用 Java CLI

迁移成本较低，但长期保留 TypeScript/CLI/Java 三份契约、每次 Java 冷启动和跨进程 Coordinator，不能达到
统一代码边界和简化维护的目标。只允许作为短期实验参考，不作为目标架构。

### 把 Coordinator 暴露为额外 MCP Tool

模型可以跳过该 Tool 直接调用其他 Tool，关键流程仍依赖 Prompt/Skill，因此不能形成强制门禁。

### 只暴露一个万能 `analysis_execute` Tool

会形成巨大联合 Schema，降低工具可发现性、参数校验、权限隔离和错误定位能力。统一性通过 Catalog、Dispatcher
和 Coordinator 实现，不通过合并所有工具实现。

### MCP Server 内部运行第二个大模型

会重复宿主已有的模型调用、上下文、Agent Loop、会话和凭据能力，并形成两个推理主体，降低可移植性和可审计性。

### 为每个宿主复制工具和工作流

会造成 Schema、Prompt、错误语义和证据规则漂移，无法比较跨宿主 Eval，也无法保证新增工具在所有宿主同时生效。

### 第一版同时实现 stdio 和远程 HTTP

当前目标是本地编程工具子 Agent。远程服务会额外引入端口、认证、多租户和部署安全，缺少真实需求支撑。

## 实施门禁

1. 关联设计文档经用户批准并转为 `Approved`。
2. 详细实施计划经用户批准并选择执行方式。
3. MCP Java SDK 依赖、离线镜像、许可证和依赖树验证通过。
4. 本地 13 个与公司 18 个工具清单完成对齐；未知能力不进入实现。
5. 宿主内置源码工具依赖审计完成。
6. 先写失败测试，再实现契约、Coordinator、MCP Server 和 Adapter。
7. 每阶段执行受影响模块测试；跨模块契约完成后执行根项目 `mvn test`。
8. Qwen 和第二宿主使用相同 MCP Tool Catalog、Agent Definition 和 Eval Suite。

## 批准记录

2026-09-28，用户批准设计 0.4、详细实施计划，并明确选择在当前根仓库原生执行。MCP SDK 2.0.1 两个锁定坐标已
完成在线与离线解析验证，许可证为 MIT；Qwen CLI 0.20.0 的 Extension、stdio MCP 与 `agents/*.md` 子 Agent
契约已经本机验证。DeepSeek Harness 的真实契约尚不可得，因此只阻塞第二宿主门禁，不授权推测实现。

2026-09-28 Task 16 依赖审查确认 SDK 2.0.1 的 Jackson 2 适配器按 2.21.1 编译；继续由根 BOM 降级到 2.17.2
会把风险推迟到 Tool Schema 校验阶段。因此统一升级 Jackson 2 BOM，并以全仓测试和 MCP Schema Validator smoke
作为兼容门禁；该修订不改变“不引入 Jackson 3/Spring”的架构选择。

## 回滚条件

- MCP 协议或宿主兼容性无法达到已批准矩阵。
- Coordinator 出现不可接受的合法分析误拦截且影子/告警阶段无法收敛。
- 长驻 Server 导致目标进程泄漏、Workspace 损坏或 Artifact 完整性回归。
- Agent Eval 在相同模型和输入下明显低于批准基线。

回滚只切换模型入口和安装配置，不删除或重写历史 Case Workspace。
