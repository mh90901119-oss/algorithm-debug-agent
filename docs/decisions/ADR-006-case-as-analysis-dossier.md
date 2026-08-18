# ADR-006：Case 采用分析档案而非工作流状态机

- 状态：Accepted（Context 转换规则由 ADR-010 修订）
- 日期：2026-08-12

## 背景

Algorithm Debug Agent 的实际入口是用户在目标算法仓库中通过 OpenCode 指定一个 JUnit UT 并提出问题。
同一问题的后续追问会复用历史 Gantt、异常、CodePathTracer 调用路径、JDWP 运行时数据和大模型分析结果，
并可能触发多次 UT 运行和最小增量采集。

原架构使用 `Case -> Inquiry -> Turn -> Collection Round` 和包含十余状态的
`CaseLifecycleState` 描述该过程。这把用户问题、对话轮次、UT 进程状态和大模型分析动作耦合成一套
可变状态机。异常 UT、断言失败但已输出 Gantt、同轮先分析再采集等实际场景无法自然映射，且容易因
非法状态转换阻断本可继续的诊断。

## 决策

1. `Case` 定义为围绕一个用户问题的不可变分析档案身份，不作为分析工作流状态机；
2. 一段围绕同一问题的 OpenCode 对话显式复用同一 `caseId`，不由 LLM 猜测自然语言相似度；
3. `Run` 表示一次目标 UT 执行，使用追加式 `run-start.json` 与 `run-outcome.json` 保存事实；
4. `Analysis` 表示一轮问题分析，使用 `analysisId` 保存问题、历史复用、证据缺口、计划、证据选择、
   分级结论和回答；
5. CodePathTracer、JDWP、Gantt、异常和日志作为不可变 Artifact 保存，Analysis 只引用标准化 Evidence；
6. UT 进程结果与 Gantt 是否存在分别建模，允许断言失败或算法异常的 Run 同时包含 Gantt 与诊断；
7. 不持久化复杂 `case-state.json`、Run Registry、Inquiry/Turn 状态机或自动状态转换；
8. Case 的当前摘要由不可变 Case、Run、Analysis 和 Evidence 记录确定性重建，不作为新的事实源；
9. 首次无采集 Run 作为复现参考；默认不重复运行两次，只有检测到漂移或用户要求时才验证非确定性；
10. 动态采集后必须与参考 Run 比较：成功运行比较 Gantt 语义 Hash，异常运行比较稳定失败特征，
    断言失败且有 Gantt 时同时比较两者；
11. 同一问题中的源码、输入或 UT 内容变化不创建新 Case；根据 ADR-010，由用户或大模型显式追加最小
    `ContextRecord`，不再自动扫描并比较 Workspace；
12. 不同 Context 之间的 Gantt/异常差异是待分析的变更事实；只有同一 Context 内无采集与动态采集
    结果不一致时，才把采集 Evidence 降级为行为干扰；
13. 大模型根据 Case Digest、Run Comparison、历史 Evidence 和用户问题自主决定是否运行 UT、读取
    Diff 或继续采集，确定性代码不以固定状态机替代该决策；
14. 每次 UT 执行采用“结构化 `RunOutcomeSummary` + 原始 Artifact 引用 + 版本化 Skill 指引”交给大模型；
    ToolResponse 必须自解释，不能只依赖 Prompt 或 Skill 补齐身份、结果和来源字段；
15. 异常诊断只确定性提取执行阶段、异常类、消息、cause 和业务栈帧，不穷举异常并推断输入或算法根因；
16. 当前 Agent Runtime 仅为 OpenCode，调用链为 OpenCode Custom Tool -> `ada` CLI -> Java Core；
    当前阶段不实现 Algorithm Debug MCP Server，也不适配其他 CLI；
17. Skill、OpenCode Agent、Custom Tool 和模板的唯一源码位于 Algorithm Debug Agent 仓库。通过一次性 OpenCode
    适配安装登记外部路径后，用户在目标算法仓库直接运行 `opencode`，不复制 Skill 到全局 Skill 目录。

## 影响

ADR-010 取代了本 ADR 中“用 Workspace 指纹自动决定 Context”的早期实现方式，但不改变 Case 作为分析档案、
Run/Analysis/Evidence 追加保存和大模型自主规划的核心决定。

- `CaseLifecycleState` 已删除；Baseline 兼容数据只使用专用 `BaselineStabilityState`，且保留原 JSON 枚举字面值；
- `InquiryId` 和 `TurnId` 已删除，OpenCode 对话通过 `caseId`、`analysisId` 关联；
- Case 只冻结项目、目标 UT selector 和问题身份；每个 Run、Analysis、Artifact 和 Evidence 均标注
  `contextId`，防止把历史运行事实误写成当前代码事实；
- 缺少 `run-outcome.json` 的 Run 被读取为不完整事实，不需要恢复任务重写为 `ABORTED`；
- Append-only 目录与不透明 ID 避免大部分并发覆盖，暂不引入复杂 Case Lock 和事件重放；
- LLM 可以灵活决定复用、静态分析或动态采集，但不能修改原始 Artifact、伪造 Evidence 或把历史假设
  自动升级为事实；
- OpenCode 用户配置仅承担外部 Skill/Custom Tool/Agent 的发现和加载，不成为领域事实源；
- `ada opencode --project` 只可作为开发或临时免安装入口，正式日常入口是一次适配后直接运行 `opencode`；
- 现有外部 JDWP-MCP 工具文档属于工具历史和可选人工调试事实，不代表当前 Agent 通过 MCP 接入 OpenCode。

## 被否决方案

- 完整 Case 状态机：状态边界与真实对话/分析动作不一致，规则复杂且会阻断异常诊断；
- 只依赖 OpenCode 聊天历史：无法在上下文压缩、重启或跨轮采集后保持证据 provenance；
- JSONL 事件溯源：审计能力强，但当前需要额外重放、修复和压缩机制，超出 Phase 0；
- SQLite 状态库：引入不必要依赖，且不利于离线复制和直接审阅证据；
- 将 Algorithm Debug Agent 实现为 MCP Server：当前 OpenCode Custom Tool 调用稳定 CLI 已足够，引入协议层会扩大
  本阶段范围；待出现第二种客户端的真实需求后再单独设计；
- 把 Skill 复制到每个目标项目或全局 Skill 目录：会产生版本漂移和多份事实源；改为一次性登记 Agent
  安装目录中的唯一 Skill 源码。
