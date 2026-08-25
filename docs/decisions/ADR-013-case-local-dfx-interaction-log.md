# ADR-013: DFX 交互日志跟随 Case 归档

- 状态：Accepted
- 日期：2026-08-22

## 背景

独立 Session 日志要求用户先查 Session ID，再映射 Case，并会把同一会话中的多个问题混在一起。
当前 Tool Runtime 已经能够确定 `projectId/caseId/analysisId` 和内部 CLI 顺序，继续增加 OpenCode Plugin、
Viewer 和 Session 副本会扩大故障面。

## 决策

1. 正常 DFX 日志保存为 `workspace/projects/<projectId>/cases/<caseId>/interaction.jsonl`。
2. 同一 Case 的多轮 Analysis 追加到同一文件，不同 Case 物理隔离。
3. `analysis_begin` 得到 Case 身份前只在内存缓冲；创建失败才写 `dfxDirectory/unassigned`。
4. MVP 在 Custom Tool Runtime 中使用独立 Recorder，不引入 OpenCode Plugin 或后台线程。
5. 日志只保存白名单诊断元数据，写入失败不得影响 ToolResponse。
6. DFX 日志不是 Artifact 或 Evidence，不参与根因结论审计。

## 结果

用户可以从 Case 目录直接复盘实际 Tool/CLI 顺序，无需脚本和 Session ID 查询。迁移到其他 CLI 时复用
事件 Schema 和 Recorder 契约，只替换调用侧 Adapter。代价是 MVP 不覆盖 OpenCode 自身的 Skill、
Permission、Session Idle 和模型推理事件；这些不属于当前核心故障定位范围。

## 被取代的设计

本 ADR 取代 2026-08-21 DFX 设计中的独立 Session 日志、OpenCode Observability Plugin、
`show-agent-log.ps1` 和 Viewer 方案。
