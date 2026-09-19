# Qwen MCP Adapter and Algorithm Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 Java 领域语义和 OpenCode 行为的前提下，为 Qwen 提供可安装的 Algorithm Debug Extension、13 个 MCP 工具、版本化 View API、目标执行串行门禁和 Qwen Eval Driver。

**Architecture:** 从 OpenCode Adapter 中提取 Host-neutral Node Runtime，OpenCode 与 Qwen MCP 共同调用它；Gateway 以 loopback HTTP 同时承载 MCP、健康/capability、只读 View API 和 SSE，并继续通过 `bin/ada.cmd` 调用 Java 21 后端。

**Tech Stack:** Node.js 22 ESM、MCP TypeScript SDK、Zod、Node Test Runner、JSON Schema、SSE、Java 21、Maven、JUnit 5

**Spec:** `docs/designs/2026-09-20-qwen-mcp-and-dual-runtime-workbench-design.md`、`docs/decisions/ADR-017-qwen-mcp-adapter-and-dual-runtime-workbench.md`

**Execution status:** Approved；等待总计划 Phase 0 基线与 Worktree 完成后执行。

## Global Constraints

- `integrations/opencode` 的外部工具名、参数、ToolResponse 和恢复提示保持兼容。
- Shared Runtime 只做参数边界、进程执行、Schema 校验、错误映射、interaction 记录和门禁，不解释算法业务。
- MCP 基础设施错误使用 MCP `isError`；目标 UT 失败是合法 ToolResponse，不能误标为 MCP 传输失败。
- Gateway 默认绑定随机 loopback 端口，要求每次启动随机 Token，不接受外部地址监听。
- 任何 JSON、stdout、stderr、SSE、View 和 Artifact 读取都有显式字节/条目/时间上限。
- View 只读取已注册且哈希校验通过的 Artifact；Raw Artifact 永不改写。
- 构建产物不提交；Qwen Skill 从规范源生成，哈希不一致即构建失败。

---

## File Map

| 路径 | 计划职责 |
|---|---|
| `integrations/shared-runtime/lib/ada-cli.mjs` | Host-neutral CLI 执行与 ToolResponse 校验 |
| `integrations/shared-runtime/lib/case-interaction-recorder.mjs` | Host-neutral interaction 追加记录 |
| `integrations/shared-runtime/lib/target-execution-gate.mjs` | `run_test`/CodePath/JDWP 全局串行 lease |
| `integrations/shared-runtime/lib/tool-runtime.mjs` | 13 工具的统一执行入口 |
| `integrations/shared-runtime/test/*.test.mjs` | OpenCode 等价性与边界测试 |
| `integrations/qwen/qwen-extension.json` | Qwen Extension 清单 |
| `integrations/qwen/src/tool-registry.mts` | 13 个 MCP 名称、输入 Schema 与 handler 映射 |
| `integrations/qwen/src/mcp-server.mts` | MCP Server 及错误语义 |
| `integrations/qwen/src/gateway-server.mts` | loopback Host、认证、健康、MCP、View、SSE |
| `integrations/qwen/src/view-projector.mts` | Artifact 到 Agent View v1 的有界投影 |
| `integrations/qwen/src/capabilities.mts` | capability 版本和握手 |
| `integrations/qwen/scripts/build-extension.mjs` | 生成可安装 Extension 并复制规范 Skill |
| `integrations/qwen/test/*.test.mts` | MCP/Gateway/View/安全测试 |
| `schemas/view/*.schema.json` | Agent View v1 Schema |
| `agent-evals/qwen-driver.mjs` | 真实 Qwen 会话 Eval 驱动 |

### Task 1: 锁定现有 OpenCode Adapter 特征

**Files:**
- Modify: `integrations/opencode/test/ada-cli.test.mjs`
- Modify: `integrations/opencode/test/tool-runtime.test.mjs`
- Modify: `integrations/opencode/test/case-interaction-recorder.test.mjs`
- Create: `integrations/shared-runtime/test/opencode-characterization.test.mjs`

**Interfaces:**
- Characterizes `runAdaCli(options)` success/timeout/spawn/invalid-JSON/oversize behavior.
- Characterizes all thirteen tool functions, Workspace injection, interaction lifecycle, and target-execution conflict.

- [ ] **Step 1: 为外部可观察行为增加 golden 特征测试**

测试覆盖有效 ToolResponse 原样返回、目标 UT 失败、CLI 非零退出、超时、stdout/stderr 超限、无效 JSON、未知 ToolResponse 主版本、interaction 成功/失败追加以及三个动态工具并发冲突。

- [ ] **Step 2: 运行测试并确认 GREEN 基线**

Run:

```powershell
$tests = Get-ChildItem integrations/opencode/test -Filter *.test.mjs
node --test $tests.FullName
```

Expected: PASS。此任务是特征锁定，不人为制造 RED。

- [ ] **Step 3: 保存稳定测试 fixture**

在 `integrations/shared-runtime/test/fixtures/` 创建去敏的 CLI 响应 fixture，包含成功、目标失败、基础设施失败和边界截断；fixture 必须使用相对 Workspace 与固定时间/ID。

- [ ] **Step 4: Commit**

```powershell
git add integrations/opencode/test integrations/shared-runtime/test
git commit -m "test: characterize adapter runtime behavior"
```

### Task 2: 提取 Host-neutral Shared Runtime

**Files:**
- Create: `integrations/shared-runtime/lib/ada-cli.mjs`
- Create: `integrations/shared-runtime/lib/case-interaction-recorder.mjs`
- Create: `integrations/shared-runtime/lib/target-execution-gate.mjs`
- Create: `integrations/shared-runtime/lib/tool-runtime.mjs`
- Create: `integrations/shared-runtime/lib/index.mjs`
- Create: `integrations/shared-runtime/test/ada-cli.test.mjs`
- Create: `integrations/shared-runtime/test/tool-runtime.test.mjs`
- Create: `integrations/shared-runtime/test/target-execution-gate.test.mjs`
- Modify: `integrations/opencode/lib/ada-cli.mjs`
- Modify: `integrations/opencode/lib/case-interaction-recorder.mjs`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`

**Interfaces:**

```js
createAlgorithmToolRuntime({ workspace, cli, recorder, executionGate, limits, clock })
TargetExecutionGate.acquire({ operation, ownerId, signal })
runAdaCli({ command, args, cwd, timeoutMs, maxStdoutBytes, maxStderrBytes, signal })
```

- [ ] **Step 1: 写 Shared Runtime 直接测试并确认 RED**

Run:

```powershell
$tests = Get-ChildItem integrations/shared-runtime/test -Filter *.test.mjs
node --test $tests.FullName
```

Expected: FAIL，因为 Shared Runtime 模块尚不存在。

- [ ] **Step 2: 移动纯运行逻辑，OpenCode 文件改为兼容 re-export/装配层**

保持 OpenCode import 路径、函数名和错误文本不变。`TargetExecutionGate` 以依赖注入方式接收时钟，不使用可泄漏到测试间的静态全局；Gateway 启动时创建唯一实例并注入所有 MCP handler。

- [ ] **Step 3: 运行 Shared 与 OpenCode 测试**

```powershell
$tests = Get-ChildItem integrations/shared-runtime/test,integrations/opencode/test -Filter *.test.mjs
node --test $tests.FullName
```

Expected: PASS，且 golden ToolResponse 字节等价。

- [ ] **Step 4: Commit**

```powershell
git add integrations/shared-runtime integrations/opencode/lib integrations/opencode/test
git commit -m "refactor: share algorithm tool runtime"
```

### Task 3: 建立 Qwen Extension 构建与 Skill 单一来源

**Files:**
- Create: `integrations/qwen/package.json`
- Create: `integrations/qwen/package-lock.json`
- Create: `integrations/qwen/tsconfig.json`
- Create: `integrations/qwen/qwen-extension.json`
- Create: `integrations/qwen/scripts/build-extension.mjs`
- Create: `integrations/qwen/scripts/verify-extension.mjs`
- Create: `integrations/qwen/test/extension-assets.test.mts`
- Modify: `.gitignore`
- Create: `THIRD_PARTY_NOTICES.md`

**Interfaces:**
- Build output: `integrations/qwen/dist/extension/`.
- Canonical input: `skills/algorithm-debug/SKILL.md`.
- Extension starts `dist/server/mcp-server.js` with host-supplied Gateway URL/Token or stdio configuration defined by the verified Qwen Extension schema.

- [ ] **Step 1: 写 manifest、生成资产和哈希失败测试**

测试证明：13 个 MCP 工具由一个 server 提供；生成的 Skill 与规范源 SHA-256 相同；manifest 不含绝对路径、密钥或开发机端口；`dist` 不被 Git 跟踪。

- [ ] **Step 2: 运行并确认 RED**

Run from `integrations/qwen`:

```powershell
npm test -- --run extension-assets
```

Expected: FAIL，因为 manifest/build script 尚未实现。

- [ ] **Step 3: 实现最小构建脚本和 Extension manifest**

锁定 MCP SDK、Zod 和 TypeScript 版本；生成目录先写临时目录，校验完成后原子替换。不得在源码树手工维护第二份 Skill。

- [ ] **Step 4: 构建并验证可重复性**

```powershell
npm ci
npm run build
npm run verify:extension
npm run build
npm run verify:extension
```

Expected: 两次 manifest、Skill 和 bundle 哈希一致。

- [ ] **Step 5: Commit**

```powershell
git add .gitignore THIRD_PARTY_NOTICES.md integrations/qwen
git commit -m "build: package qwen algorithm extension"
```

### Task 4: 实现 13 个 MCP 工具与契约等价性

**Files:**
- Create: `integrations/qwen/src/tool-registry.mts`
- Create: `integrations/qwen/src/mcp-errors.mts`
- Create: `integrations/qwen/src/mcp-server.mts`
- Create: `integrations/qwen/test/tool-registry.test.mts`
- Create: `integrations/qwen/test/mcp-server.test.mts`
- Create: `integrations/qwen/test/open-code-parity.test.mts`

**Interfaces:**
- Tools: `analysis_begin`, `case_inspect`, `algorithm_input_capture`, `case_audit`, `gantt_inspect`, `run_test`, `static_analyze`, `codepath_plan_create`, `codepath_collect`, `jdwp_plan_create`, `jdwp_collect`, `artifact_read`, `evidence_query`.
- Successful MCP content contains exactly one bounded ToolResponse 2.0 JSON text item.
- Transport/schema/spawn/timeout/incompatible-response failures set `isError: true`; a ToolResponse describing a failed target test remains `isError: false`.

- [ ] **Step 1: 写 discovery、Schema 和错误分类测试**

逐一断言 13 个工具名称、required/optional 字段、未知字段拒绝、数值/字符串上限、Workspace 由可信上下文注入而不是模型输入。

- [ ] **Step 2: 运行并确认 RED**

```powershell
npm test -- --run tool-registry mcp-server open-code-parity
```

Expected: FAIL，因为 registry/server 尚不存在。

- [ ] **Step 3: 实现 Registry 和 Server**

输入 Schema 必须共享或从当前 OpenCode/CLI 契约生成；不得复制不同默认值。handler 只调用 `createAlgorithmToolRuntime`，不直接 spawn Java。

- [ ] **Step 4: 执行 parity matrix**

对 13 个工具逐个运行有效请求和至少一个无效请求，比较 OpenCode 与 MCP 的 ToolResponse JSON、错误码、Artifact 引用和 interaction 记录。

- [ ] **Step 5: Commit**

```powershell
git add integrations/qwen/src integrations/qwen/test
git commit -m "feat: expose algorithm tools over mcp"
```

### Task 5: 实现 Gateway 生命周期、认证和目标执行门禁

**Files:**
- Create: `integrations/qwen/src/capabilities.mts`
- Create: `integrations/qwen/src/gateway-config.mts`
- Create: `integrations/qwen/src/gateway-server.mts`
- Create: `integrations/qwen/src/sse-broker.mts`
- Create: `integrations/qwen/test/capabilities.test.mts`
- Create: `integrations/qwen/test/gateway-server.test.mts`
- Create: `integrations/qwen/test/gateway-security.test.mts`
- Modify: `integrations/shared-runtime/test/target-execution-gate.test.mjs`

**Interfaces:**
- `GET /api/v1/capabilities`
- `GET /api/v1/health`
- MCP endpoint selected from the verified Qwen-supported transport.
- `GET /api/v1/analyses/{analysisId}/events`
- Capability fields: `agentVersion`, `toolResponseVersion`, `viewApiVersion`, `skillVersion`, `supportedTools`.

- [ ] **Step 1: 写 loopback、Token、版本和并发 RED 测试**

覆盖非 loopback bind 拒绝、缺失/错误 Token、Origin 不符、未知 major、请求体超限、慢请求超时、SSE 客户端上限、`run_test`/`codepath_collect`/`jdwp_collect` 两两冲突和 lease 异常释放。

- [ ] **Step 2: 实现 Gateway 状态机**

状态为 `STARTING -> READY -> DRAINING -> STOPPED` 或 `FAILED`；停止时先拒绝新请求，再取消/等待有界活动操作，最后关闭 listener。健康响应不得返回 Token、环境变量或敏感本地路径。

- [ ] **Step 3: 运行测试**

```powershell
npm test -- --run capabilities gateway target-execution-gate
```

Expected: PASS，且并发动态操作只启动一个 CLI 子进程。

- [ ] **Step 4: Commit**

```powershell
git add integrations/qwen/src integrations/qwen/test integrations/shared-runtime/test
git commit -m "feat: add secured algorithm gateway"
```

### Task 6: 定义并实现 Agent View v1

**Files:**
- Create: `schemas/view/analysis-overview-v1.schema.json`
- Create: `schemas/view/algorithm-input-v1.schema.json`
- Create: `schemas/view/source-architecture-v1.schema.json`
- Create: `schemas/view/runtime-path-v1.schema.json`
- Create: `schemas/view/runtime-state-v1.schema.json`
- Create: `schemas/view/evidence-matrix-v1.schema.json`
- Create: `schemas/view/conclusion-v1.schema.json`
- Create: `integrations/qwen/src/view-contracts.mts`
- Create: `integrations/qwen/src/artifact-reader.mts`
- Create: `integrations/qwen/src/view-projector.mts`
- Create: `integrations/qwen/src/view-routes.mts`
- Create: `integrations/qwen/test/view-contracts.test.mts`
- Create: `integrations/qwen/test/view-projector.test.mts`
- Create: `integrations/qwen/test/view-routes.test.mts`

**Interfaces:**
- `GET /api/v1/cases`
- `GET /api/v1/cases/{caseId}/overview`
- `GET /api/v1/analyses/{analysisId}/input`
- `GET /api/v1/analyses/{analysisId}/source`
- `GET /api/v1/analyses/{analysisId}/runtime`
- `GET /api/v1/analyses/{analysisId}/evidence`
- `GET /api/v1/analyses/{analysisId}/conclusion`

- [ ] **Step 1: 先写 Schema 和非法投影测试**

每个 View 必须含 `schemaVersion`、`caseId`、`analysisId`、`generatedAt`、`sourceArtifactIds`、`coverage`、`limitations`。测试拒绝不存在/未注册/哈希不符 Artifact、路径穿越、未知 evidence level、无界数组和超大字符串。

- [ ] **Step 2: 运行并确认 RED**

```powershell
npm test -- --run view-contracts view-projector view-routes
```

- [ ] **Step 3: 实现流式/有界投影**

默认上限写入 `gateway-config` 并在响应中报告截断。证据等级严格限定为五类；缺少证据返回 `MISSING_EVIDENCE` 或 limitations，不补造字段。

- [ ] **Step 4: 验证 Raw Artifact 未变化**

投影测试在调用前后计算 fixture SHA-256，并断言一致；同一输入、固定时钟下 View JSON 稳定。

- [ ] **Step 5: Commit**

```powershell
git add schemas/view integrations/qwen/src integrations/qwen/test
git commit -m "feat: project versioned algorithm views"
```

### Task 7: 增加 Qwen Eval Driver 与安装诊断

**Files:**
- Create: `agent-evals/qwen-driver.mjs`
- Create: `agent-evals/test/qwen-driver.test.mjs`
- Modify: `agent-evals/run.mjs`
- Modify: `agent-evals/README.md`
- Create: `integrations/qwen/src/doctor.mts`
- Create: `integrations/qwen/test/doctor.test.mts`
- Create: `integrations/qwen/README.md`
- Modify: `README.md`
- Modify: `docs/current-capabilities.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`

**Interfaces:**
- Eval records include runtime, model ID, code SHA, Skill hash, extension version, ToolResponse version, View API version, timestamps, tool events and grading inputs.
- Doctor checks Java 21, `ada.cmd`, built JARs, writable Case root, Qwen Extension assets, Gateway compatibility and target path; it never prints credentials.

- [ ] **Step 1: 写 fake Qwen transcript/stream 驱动 RED 测试**

覆盖正常 completion、工具循环、模型错误、Gateway 错误、超时、取消、无最终回答、未知事件和敏感字段脱敏。

- [ ] **Step 2: 实现 Driver 与 Doctor**

Driver 复用现有 Suite/grade 契约，不产生第二种评分格式；Doctor 失败返回稳定错误码和用户可执行恢复建议。

- [ ] **Step 3: 运行 Node 测试**

```powershell
$tests = Get-ChildItem agent-evals/test,integrations/shared-runtime/test,integrations/opencode/test -Filter *.test.mjs
node --test $tests.FullName
Set-Location integrations/qwen
npm test
```

Expected: PASS。

- [ ] **Step 4: 更新文档并 Commit**

```powershell
git add agent-evals integrations/qwen README.md docs/current-capabilities.md docs/algorithm-debug-workflow-and-artifacts.md
git commit -m "feat: add qwen evaluation and diagnostics"
```

### Task 8: 算法仓完整门禁与阶段审计

**Files:**
- Create: `docs/audits/2026-09-20-qwen-mcp-gateway-implementation-audit.md`
- Modify if behavior changed: `docs/architecture/tool-validation-baseline.md`

- [ ] **Step 1: 执行格式、依赖、Schema 和 Extension 检查**

Run:

```powershell
Set-Location integrations/qwen
npm ci
npm run build
npm test
npm run verify:extension
Set-Location ../..
git diff --check
```

- [ ] **Step 2: 执行全部 Java 与 Node 回归**

```powershell
mvn -Pcodepath-launcher test
$tests = Get-ChildItem agent-evals/test,integrations/shared-runtime/test,integrations/opencode/test -Filter *.test.mjs
node --test $tests.FullName
```

Expected: 全部通过；不能以编译成功代替行为测试。

- [ ] **Step 3: 运行真实 Qwen CLI 10-case Smoke**

通过构建后的 Extension 和公司模型配置运行 `agent-evals/suites/smoke.json`，记录版本化输出；失败不能在此阶段通过改 grader 阈值绕过。

- [ ] **Step 4: 编写审计并进行代码审查**

审计列出所有命令、测试数、Smoke 结果、依赖许可证、性能预算、已知限制和精确 Git SHA。使用 `superpowers:requesting-code-review` 审查后修复 P0/P1 问题。

- [ ] **Step 5: Commit**

```powershell
git add docs/audits docs/architecture/tool-validation-baseline.md
git commit -m "docs: audit qwen mcp gateway implementation"
```

## Algorithm Repository Definition of Done

- OpenCode 全套测试与原行为通过，未删除任何现有能力。
- Qwen Extension 可重复构建，不含绝对路径/凭据，Skill 哈希等于规范源。
- 13 个 MCP 工具 discovery、Schema、成功/失败语义与 OpenCode 基线一致。
- Gateway 仅 loopback、强 Token、有界、可停止，三个动态执行工具全局串行。
- 七类 Agent View 通过 Schema、provenance、哈希、边界和确定性测试。
- Qwen Eval Driver 与现有 grader 兼容，真实 10-case Smoke 已产生可追溯记录。
- Java、OpenCode、Shared Runtime、Qwen 集成全部测试通过并有阶段审计。
