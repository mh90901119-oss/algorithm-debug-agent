# ADR-006：Case 采用分析档案而非工作流状态机

- 状态：Accepted
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
    断言失败且有 Gantt 时同时比较两者。

## 影响

- `CaseLifecycleState` 不再驱动后续分析流程；现有类型在迁移期保留，但新持久化 API 不依赖它；
- `InquiryId` 和 `TurnId` 不进入本次持久化切片，OpenCode 对话通过 `caseId`、`analysisId` 关联；
- 缺少 `run-outcome.json` 的 Run 被读取为不完整事实，不需要恢复任务重写为 `ABORTED`；
- Append-only 目录与不透明 ID 避免大部分并发覆盖，暂不引入复杂 Case Lock 和事件重放；
- LLM 可以灵活决定复用、静态分析或动态采集，但不能修改原始 Artifact、伪造 Evidence 或把历史假设
  自动升级为事实。

## 被否决方案

- 完整 Case 状态机：状态边界与真实对话/分析动作不一致，规则复杂且会阻断异常诊断；
- 只依赖 OpenCode 聊天历史：无法在上下文压缩、重启或跨轮采集后保持证据 provenance；
- JSONL 事件溯源：审计能力强，但当前需要额外重放、修复和压缩机制，超出 Phase 0；
- SQLite 状态库：引入不必要依赖，且不利于离线复制和直接审阅证据。
