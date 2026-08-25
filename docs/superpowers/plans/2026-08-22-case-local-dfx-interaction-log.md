# Case-local DFX Interaction Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每个 Algorithm Debug Case 自动生成可直接打开的 `interaction.jsonl`，按真实顺序记录 Custom Tool 与 Java CLI 调用，同时保证日志失败不影响 Agent 业务结果。

**Architecture:** 在现有 OpenCode `tool-runtime.mjs` 中接入一个独立、可复用的 Case Interaction Recorder。Recorder 在 `analysis_begin` 返回 Case 身份前只在内存暂存事件，成功后写入 `workspace/projects/<projectId>/cases/<caseId>/interaction.jsonl`；无法创建 Case 的调用失败写入现有 `dfxDirectory/unassigned/<sessionID>.jsonl`。MVP 不增加查看脚本、后台线程、数据库、Web UI 或 OpenCode Plugin。

**Tech Stack:** JavaScript ESM、Node/Bun 文件 API、OpenCode Custom Tool context、JSONL、Node Test Runner、PowerShell 安装器。

**Spec:** `docs/designs/2026-08-21-agent-dfx-observability-design.md`

## Global Constraints

- 正常 Case 日志只写 `cases/<caseId>/interaction.jsonl`，不得另写 Session 副本。
- `dfxDirectory` 只保存无法归属到 Case 的启动或 `analysis_begin` 失败事件。
- `dfxEnabled=false` 时不得创建任何 DFX 文件。
- 日志不得包含用户问题正文、模型回答正文、隐藏推理、Tool 完整参数、Tool 完整响应、stdout/stderr、算法 JSON、局部变量、凭据或绝对业务路径。
- 单事件最大 8 KiB；单 Case 日志最大 16 MiB；达到上限后最多写一条 `LOG_TRUNCATED`。
- 日志写入、目录创建、序列化和截断处理失败必须非阻断，不得改变原 ToolResponse。
- 使用现有 `workspaceDirectory`、`dfxDirectory` 和 `dfxEnabled`，不增加配置文件、环境变量或命令行路径参数。
- 不实现 `show-agent-log.ps1`；日志必须可由 VS Code 或普通文本编辑器直接打开。
- 不记录推测事件。OpenCode 没有明确 Skill-load Hook，因此不得伪造 `SKILL_LOADED`。
- 本计划不执行 Git 操作；只有用户明确要求时才提交。

---

## File Map

| 文件 | 职责 |
|---|---|
| `schemas/dfx/case-interaction-event-v1.schema.json` | 版本化事件字段、枚举和大小边界 |
| `integrations/opencode/lib/case-interaction-recorder.mjs` | Case 路由、缓冲、有界追加、脱敏和非阻断降级 |
| `integrations/opencode/lib/tool-runtime.mjs` | 在 10 个 Custom Tool 和每个内部 CLI 调用周围产生确定性事件 |
| `integrations/opencode/tools/algorithm-debug.ts` | 将安装后的 Workspace、fallback DFX 和开关注入 Runtime |
| `scripts/install-opencode.ps1` | 安装 Recorder 模块并在 Check 中核对副本 |
| `integrations/opencode/test/case-interaction-recorder.test.mjs` | Writer、路由、脱敏、预算和失败降级测试 |
| `integrations/opencode/test/tool-runtime.test.mjs` | Tool/CLI 顺序、Case 绑定和多 Case 隔离测试 |
| `integrations/opencode/test/configuration-assets.test.mjs` | Schema、安装资产、UTF-8 和禁止内容测试 |
| `docs/designs/2026-08-21-agent-dfx-observability-design.md` | 用 Case-local 方案取代旧 Session/Viewer 方案 |
| `docs/decisions/ADR-013-case-local-dfx-interaction-log.md` | 记录为何不采用独立 Session 日志和 MVP Plugin |
| `README.md`、`docs/algorithm-debug-workflow-and-artifacts.md` | 手工复盘路径、字段和边界 |

---

### Task 1: 冻结 Case Interaction Event 契约

**Files:**

- Create: `schemas/dfx/case-interaction-event-v1.schema.json`
- Create: `docs/decisions/ADR-013-case-local-dfx-interaction-log.md`
- Modify: `docs/designs/2026-08-21-agent-dfx-observability-design.md`
- Test: `integrations/opencode/test/configuration-assets.test.mjs`

**Interfaces:**

- Produces: `CaseInteractionEvent v1.0`，供 Recorder 和测试共同遵守。
- Consumes: 现有 Tool 名称、ToolResponse 2.0 代码和 Case/Analysis/Run/Collection/Evidence ID。

- [ ] **Step 1: 写失败的 Schema 资产测试**

测试必须断言 Schema 文件存在、`additionalProperties=false`，并要求以下字段：

```json
{
  "schemaVersion": "1.0",
  "timestamp": "2026-08-22T10:01:01.123Z",
  "level": "INFO",
  "eventType": "TOOL_CALL_COMPLETED",
  "source": "OPENCODE_TOOL_RUNTIME",
  "outcome": "SUCCEEDED",
  "sessionId": "session-001",
  "invocationId": "invocation-001"
}
```

允许的 `eventType` 固定为：

```text
CASE_INTERACTION_STARTED
TOOL_CALL_STARTED
TOOL_CALL_COMPLETED
TOOL_CALL_FAILED
CLI_PROCESS_STARTED
CLI_PROCESS_COMPLETED
CLI_PROCESS_FAILED
LOG_TRUNCATED
```

可选字段固定为：

```text
messageId, agent, projectId, caseId, analysisId, toolName,
commandName, durationMillis, code, targetTest, runId, planId,
collectionId, evidenceId, artifactIds
```

- [ ] **Step 2: 运行资产测试并确认因 Schema 缺失失败**

```powershell
node --test integrations/opencode/test/configuration-assets.test.mjs
```

- [ ] **Step 3: 创建最小 Schema**

字符串 ID 最大 128 字符，`targetTest` 最大 512 字符，`code` 最大 128 字符，`artifactIds` 最大 64 项，`durationMillis` 为非负整数。禁止任意 `details`、`args`、`response` 和 `message` 对象。

- [ ] **Step 4: 更新设计与 ADR**

ADR 必须明确：正常日志跟随 Case；`dfxDirectory` 仅为未归属失败；MVP 使用 Tool Runtime Recorder 而非 OpenCode Plugin；后续 CLI Adapter 可复用 Recorder 契约。

- [ ] **Step 5: 运行资产测试并确认通过**

```powershell
node --test integrations/opencode/test/configuration-assets.test.mjs
```

---

### Task 2: 实现有界、非阻断的 Case Interaction Recorder

**Files:**

- Create: `integrations/opencode/lib/case-interaction-recorder.mjs`
- Create: `integrations/opencode/test/case-interaction-recorder.test.mjs`

**Interfaces:**

- Produces: `createCaseInteractionRecorder(options)`。
- Produces: `recorder.beginTool(identity)`，返回一个 invocation scope。
- Produces: scope 方法 `bindProject(projectId)`、`bindCase(identity)`、`cliStarted(commandName)`、`cliCompleted(result)`、`cliFailed(code)`、`toolCompleted(result)`、`toolFailed(code)`。

- [ ] **Step 1: 写失败测试覆盖正常新 Case 路由**

```js
const recorder = createCaseInteractionRecorder({
  enabled: true,
  workspaceDirectory,
  fallbackDirectory,
  now: fixedClock,
})
const scope = recorder.beginTool({
  sessionId: "session-1",
  messageId: "message-1",
  agent: "algorithm-debug",
  toolName: "analysis_begin",
})
scope.bindProject("project-1")
scope.bindCase({
  caseId: "case-1",
  analysisId: "analysis-1",
  targetTest: "demo.Test#case1",
})
await scope.toolCompleted({ code: "OK" })
```

断言只生成：

```text
workspace/projects/project-1/cases/case-1/interaction.jsonl
```

- [ ] **Step 2: 写失败测试覆盖多 Case 隔离**

同一 `sessionId` 创建 `case-1` 和 `case-2`，断言两个文件互不包含对方的 `caseId`、UT 和事件。

- [ ] **Step 3: 写失败测试覆盖未归属失败**

未调用 `bindCase` 就执行 `toolFailed`，断言只生成：

```text
fallbackDirectory/unassigned/session-1.jsonl
```

- [ ] **Step 4: 写失败测试覆盖安全和预算**

断言绝对路径、问题正文、返回正文、控制字符不落盘；单事件超限时丢弃非必要字段；文件超限后只出现一次 `LOG_TRUNCATED`。

- [ ] **Step 5: 写失败测试覆盖日志不可写**

注入会抛异常的 `appendLine`，断言所有 scope 方法都 resolve，原 Tool 结果不被替换，最多调用一次安全错误回调。

- [ ] **Step 6: 运行 Recorder 测试并确认失败**

```powershell
node --test integrations/opencode/test/case-interaction-recorder.test.mjs
```

- [ ] **Step 7: 实现最小 Recorder**

实现要求：每个目标文件一条 Promise 写队列；路径必须在 Workspace Case 或 fallback 边界内；每个 JSON 事件一次 `appendFile`；事件只由显式 allowlist 字段构造；不开后台线程。

- [ ] **Step 8: 运行 Recorder 测试并确认通过**

```powershell
node --test integrations/opencode/test/case-interaction-recorder.test.mjs
```

---

### Task 3: 在 Tool Runtime 中记录真实 Tool 与 CLI 顺序

**Files:**

- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `integrations/opencode/test/tool-runtime.test.mjs`

**Interfaces:**

- Consumes: `interactionRecorder` 可选依赖；缺省为 no-op。
- Produces: 每次公开 Runtime 方法恰好一个 Tool start 和一个 completed/failed 事件。
- Produces: 每次内部 `execute(args, cwd)` 恰好一个 CLI start 和一个 completed/failed 事件。

- [ ] **Step 1: 写失败测试固定事件顺序**

`analysisBegin` 成功时要求顺序：

```text
TOOL_CALL_STARTED analysis_begin
CLI_PROCESS_STARTED workspace init
CLI_PROCESS_COMPLETED workspace init
CLI_PROCESS_STARTED project register
CLI_PROCESS_COMPLETED project register
CLI_PROCESS_STARTED case open
CLI_PROCESS_COMPLETED case open
CASE_INTERACTION_STARTED
TOOL_CALL_COMPLETED analysis_begin
```

- [ ] **Step 2: 写失败测试固定现有 Case 路由**

`runTest` 输入已有 `caseId/analysisId`，项目注册返回 `projectId` 后必须在启动业务 CLI 前绑定 Case；目标 UT 失败但 ToolResponse 成功时记录 `TOOL_CALL_COMPLETED`，不得记录 Tool crash。

- [ ] **Step 3: 写失败测试覆盖异常和准备失败**

`execute` 抛异常时记录 `CLI_PROCESS_FAILED` 和 `TOOL_CALL_FAILED`；Workspace/project preparation 返回结构化失败时保留具体 ToolResponse code。

- [ ] **Step 4: 写失败测试覆盖 Recorder 自身失败**

Recorder 每个方法都抛异常，断言 Runtime 返回值与未启用 DFX 时完全一致。

- [ ] **Step 5: 实现 `executeObserved` 和 `runObservedTool`**

`executeObserved` 只记录安全 `commandName`，例如 `workspace init`、`project register`、`case open`、`run execute`；不得记录参数数组。`runObservedTool` 从 OpenCode context 读取官方字段 `agent/sessionID/messageID`。

- [ ] **Step 6: 在 10 个 Runtime 方法接入相同包装**

```text
analysis_begin, case_inspect, run_test, static_analyze,
codepath_plan_create, codepath_collect, jdwp_plan_create,
jdwp_collect, artifact_read, analysis_complete
```

- [ ] **Step 7: 运行 Tool Runtime 测试**

```powershell
node --test integrations/opencode/test/tool-runtime.test.mjs
```

---

### Task 4: 注入安装配置并保持关闭开关有效

**Files:**

- Modify: `integrations/opencode/tools/algorithm-debug.ts`
- Modify: `integrations/opencode/lib/installation.mjs`
- Modify: `integrations/opencode/test/installation-config.test.mjs`

**Interfaces:**

- Consumes: 已安装的 `workspaceDirectory`、`dfxDirectory`、`dfxEnabled`。
- Produces: 一个进程内共享 Recorder 实例，供所有 algorithm-debug Tool 使用。

- [ ] **Step 1: 写失败测试断言 `dfxEnabled=false` 不创建文件**

- [ ] **Step 2: 写失败测试断言 Tool 不接受任何 DFX 路径参数**

静态检查 Tool Schema 中不得出现 `logPath`、`dfxPath`、`workspacePath`、`outputPath`。

- [ ] **Step 3: 创建并注入 Recorder**

```js
const interactionRecorder = createCaseInteractionRecorder({
  enabled: dfxEnabled,
  workspaceDirectory,
  fallbackDirectory: dfxDirectory,
})
```

- [ ] **Step 4: 运行配置与 Runtime 测试**

```powershell
node --test integrations/opencode/test/installation-config.test.mjs integrations/opencode/test/tool-runtime.test.mjs
```

---

### Task 5: 安装 Recorder 资产并验证幂等性

**Files:**

- Modify: `scripts/install-opencode.ps1`
- Modify: `scripts/verify-opencode-installer.ps1`
- Test: `integrations/opencode/test/configuration-assets.test.mjs`

**Interfaces:**

- Produces: OpenCode 全局配置中的 `lib/case-interaction-recorder.mjs`。
- Consumes: 现有备份、字节一致性和 capability discovery 机制。

- [ ] **Step 1: 写失败资产测试断言 Recorder 被安装**

- [ ] **Step 2: 将 Recorder 加入 `$assets`**

- [ ] **Step 3: 扩展安装器验证脚本**

第一次安装创建文件；第二次相同安装不产生新备份；Check 发现内容差异时失败；测试继续使用临时 USERPROFILE/LOCALAPPDATA。

- [ ] **Step 4: 运行安装器验证**

```powershell
.\scripts\verify-opencode-installer.ps1
```

---

### Task 6: 同步 Skill、Agent 和手工复盘文档

**Files:**

- Modify: `README.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `docs/current-capabilities.md`
- Modify: `integrations/opencode/README.md`
- Modify: `skills/algorithm-debug/SKILL.md`

**Interfaces:**

- Produces: 无脚本的手工复盘说明。

- [ ] **Step 1: 文档明确 Case 目录新增文件**

```text
workspace/projects/<projectId>/cases/<caseId>/interaction.jsonl
```

- [ ] **Step 2: 文档给出复盘跳转规则**

从 `interaction.jsonl` 中的 `runId/collectionId/evidenceId/artifactIds` 跳转到同一 Case 的对应目录；不得把 DFX 日志当作根因证据。

- [ ] **Step 3: 文档明确内容边界**

日志展示实际 Tool/CLI 行为，不展示隐藏推理、完整问题、完整回答或算法数据。Skill 不引用 `interaction.jsonl` 作为 `CONFIRMED_FACT`。

- [ ] **Step 4: 删除 Viewer 和独立 Session 正常日志的旧描述**

保留 fallback DFX 目录说明，只用于没有 Case 的失败。

---

### Task 7: 完整自动化回归与真实 OpenCode 验收

**Files:**

- Modify: `agent-evals/suites/smoke.json` only if a deterministic DFX assertion field is required
- Modify: `agent-evals/grade.mjs` only if the existing report cannot verify the file without reading sensitive content
- Test: Existing Node, Maven, installer, launcher, and real OpenCode flows

**Interfaces:**

- Produces: 可复现验收证据，不修改目标算法源码。

- [ ] **Step 1: 运行全部 OpenCode 与 Eval Node 测试**

```powershell
$tests = @(
  Get-ChildItem integrations/opencode/test -Filter *.test.mjs -File | ForEach-Object FullName
  Get-ChildItem agent-evals/test -Filter *.test.mjs -File | ForEach-Object FullName
)
node --test @tests
```

- [ ] **Step 2: 运行根 Maven 回归**

```powershell
mvn test
```

- [ ] **Step 3: 运行安装器与启动器验证**

```powershell
.\scripts\verify-opencode-installer.ps1
.\scripts\verify-ada-launcher.ps1
```

- [ ] **Step 4: 真实安装并重启 OpenCode**

```powershell
.\scripts\install-opencode.ps1 -Mode Install
```

- [ ] **Step 5: 用一个真实 UT 完成一次 Agent 分析**

验收文件必须位于对应 Case 根目录；至少包含 `analysis_begin`、`run_test`、`analysis_complete` 的 Tool 事件及内部 CLI 事件；所有行必须是有效 JSON。

- [ ] **Step 6: 验证多 Case 隔离**

同一 OpenCode Session 创建第二个 Case，确认两个 Case 的 `interaction.jsonl` 不交叉，且每个文件中的 `caseId` 恒定。

- [ ] **Step 7: 验证安全边界**

搜索日志确认不包含目标项目绝对路径、Workspace 绝对路径、用户问题正文、最终回答正文、stdout/stderr 正文、算法结果正文和环境变量值。

- [ ] **Step 8: 验证非阻断降级**

使用注入失败 Writer 的自动化测试证明日志不可写不改变 ToolResponse；不通过修改真实目录权限制造不可恢复的本机状态。

---

## Acceptance Criteria

1. 每个成功建立的 Case 根目录存在且只存在一个 `interaction.jsonl`。
2. 一个 Case 的多轮 Analysis 追加到同一文件，并通过 `analysisId` 区分。
3. 同一 Session 的不同 Case 日志物理隔离。
4. 新 Case 在 `analysis_begin` 返回身份前的事件被缓冲并写入正确 Case。
5. 无法建立 Case 的失败只写 `dfxDirectory/unassigned`。
6. 用户可直接打开 JSONL 复盘 Tool 与 CLI 的实际顺序，不依赖查看脚本。
7. DFX 文件不进入 ArtifactReference、Evidence Bundle 或 `analysis_complete` 的证据引用。
8. DFX 关闭、失败或截断均不改变 Agent 业务行为。
9. 没有新的用户路径参数、环境变量或配置文件。
10. Node、Maven、安装器、启动器和真实 OpenCode 验收全部通过。
