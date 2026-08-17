# Run Outcome 与 OpenCode Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让每次目标 UT 执行以“结构化摘要 + 原始产物引用 + Skill 指引”交付给 OpenCode，并允许失败 UT 保留异常事实和已生成的 Gantt。

**Architecture:** `ada-contracts` 定义稳定且与业务异常类型无关的运行结果协议，`debug-harness` 负责从 Maven/Surefire 产物确定性提取事实，仓库根目录 `skills/` 保存客户端无关的规范 Skill，`integrations/opencode/` 只保存薄适配层。当前不新增 Agent MCP Server；OpenCode 工具仅调用 `ada` CLI，CLI 的完整编排按后续垂直切片接入。

**Tech Stack:** Java 21、Maven、JUnit 5、JDK DOM XML Parser、JSON Schema Draft 2020-12、OpenCode Skill/TypeScript Custom Tool。

## Global Constraints

- 不穷举 Java/业务异常；目标失败只输出 `BUILD_FAILURE`、`TEST_FAILURE`、`TEST_ERROR`、`TEST_NOT_EXECUTED`、`UNKNOWN`；Agent 失败使用独立诊断。
- 目标异常与 Agent 异常独立，失败或断言失败的 UT 仍可引用 Gantt。
- 原始 stdout、stderr、Surefire XML 和 Gantt 只读保存；摘要只带有界文本与引用。
- `ToolResponse` 删除固定动作状态机属于破坏性变更，Schema 从 `1.0` 升级到 `2.0`，并同步迁移说明与测试。
- 本阶段仅适配 OpenCode，不实现 Codex/Qwen 适配器，也不新增 Agent MCP Server。
- 每个行为任务严格执行 Red-Green-Refactor，并运行受影响模块测试；最终执行根项目 `mvn test`。

---

### Task 1: 运行结果公共契约与版本化 Schema

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ContextId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/FailureCategory.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/TargetFailureDiagnostic.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunOutcomeSummary.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ToolResponse.java`
- Create: `schemas/execution/run-outcome-summary-v1.schema.json`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunOutcomeSummaryTest.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/ToolResponseTest.java`
- Modify: `ada-contracts/README.md`
- Modify: `schemas/README.md`

**Interfaces:**
- Produces: `ContextId(String value)`；`TargetFailureDiagnostic(FailureCategory, String, String, String, String)`；`RunOutcomeSummary` 及 `ToolResponse<T>` 2.0。
- Consumes: 已有 `CaseId`、`AnalysisId`、`RunId`、`ArtifactReference` 和 `ContractChecks`。

- [ ] **Step 1: Write the failing contract tests**

```java
@Test
void shouldKeepFailureAndGanttIndependent() {
    var summary = RunOutcomeSummary.failedWithArtifacts(
            new CaseId("case-1"), new ContextId("ctx-1"),
            new AnalysisId("analysis-1"), new RunId("run-1"),
            new TargetFailureDiagnostic(FailureCategory.TEST_ERROR,
                    "java.lang.NullPointerException", "value was null", "root cause", "a.b.C.run(C.java:42)"),
            List.of(ganttArtifact));
    assertEquals(FailureCategory.TEST_ERROR, summary.targetFailure().orElseThrow().category());
    assertTrue(summary.artifacts().stream().anyMatch(a -> a.artifactType().equals("GANTT")));
}
```

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -pl ada-contracts -am test`
Expected: FAIL because the new contract types do not exist and `ToolResponse` still exposes `nextAllowedActions`.

- [ ] **Step 3: Implement immutable contracts and schemas**

Implement generic exception facts with bounded nonblank validation, defensive copies, `latestRunForAnalysis`, independent process/test/Gantt/Agent fields, and artifact references. Change `SchemaVersions.TOOL_RESPONSE` to `2.0`; remove `nextAllowedActions` from record and factories. Schema required fields must match the Java DTO exactly.

- [ ] **Step 4: Run contract tests to verify GREEN**

Run: `mvn -pl ada-contracts -am test`
Expected: PASS with JSON round-trip, immutability, invalid-value, and failed-with-Gantt coverage.

- [ ] **Step 5: Audit and commit Task 1**

Run: `rg -n "nextAllowedActions|TOOL_RESPONSE" ada-contracts schemas docs`
Expected: old field appears only in migration/history text. Commit message: `feat: add structured run outcome contracts`.

### Task 2: Surefire 通用诊断与失败 Gantt 解耦

**Files:**
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireDiagnosticReader.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireDiagnosticException.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleRunResult.java`
- Create: `debug-harness/src/test/java/org/example/algorithmdebug/harness/SurefireDiagnosticReaderTest.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`
- Create: `debug-harness/src/test/resources/surefire/TEST-assertion.xml`
- Create: `debug-harness/src/test/resources/surefire/TEST-error.xml`
- Modify: `debug-harness/README.md`

**Interfaces:**
- Consumes: `FailureCategory` and `TargetFailureDiagnostic` from Task 1.
- Produces: `Optional<TargetFailureDiagnostic> SurefireDiagnosticReader.read(Path reportsDirectory, String targetTest)`.

- [ ] **Step 1: Write failing parser and failed-Gantt tests**

```java
@Test
void shouldExtractErrorWithoutInferringBusinessCause() {
    var diagnostic = reader.read(fixture("TEST-error.xml"), "a.b.TargetTest#runs").orElseThrow();
    assertEquals(FailureCategory.TEST_ERROR, diagnostic.category());
    assertEquals("java.lang.NullPointerException", diagnostic.exceptionClass());
    assertEquals("a.b.Algorithm.solve(Algorithm.java:42)", diagnostic.stableBusinessFrame());
}
```

Add a regression test constructing `ScheduleRunResult` with `RunCompletion.FAILED` and a present captured Gantt; it must be accepted.

- [ ] **Step 2: Run tests to verify RED**

Run: `mvn -pl debug-harness -am test`
Expected: FAIL because the reader is absent and `ScheduleRunResult` rejects failed runs with a schedule result.

- [ ] **Step 3: Implement secure deterministic extraction**

Use namespace-aware JDK DOM parsing with DTD and external entities disabled. Read matching `<testcase>` elements; map `<failure>` to `TEST_FAILURE` and `<error>` to `TEST_ERROR`; extract element `type`, normalized `message`, first `Caused by:` line, and first non-JDK/non-JUnit/non-Surefire stack frame. Never map exception names/messages to algorithm-specific causes.

- [ ] **Step 4: Decouple schedule capture from process success**

Keep only null/immutability validation in `ScheduleRunResult`; document that `run.completion()` and `scheduleResult` are orthogonal facts.

- [ ] **Step 5: Run tests, audit XML safety, and commit Task 2**

Run: `mvn -pl debug-harness -am test`
Expected: PASS, including XXE rejection, missing-report, assertion, error, and failed-with-Gantt regression tests. Commit message: `feat: extract target test failure diagnostics`.

### Task 3: 规范 Skill 与 OpenCode 薄适配目录

**Files:**
- Create: `skills/algorithm-debug/SKILL.md`
- Create: `integrations/opencode/README.md`
- Create: `integrations/opencode/tools/algorithm-debug.ts`
- Create: `integrations/opencode/agents/algorithm-debug.md`
- Create: `integrations/opencode/commands/debug-case.md`
- Modify: `.opencode/README.md`
- Modify: `.opencode/skills/algorithm-debug/SKILL.md`
- Modify: `.opencode/agents/algorithm-debug.md`
- Modify: `distribution/README.md`

**Interfaces:**
- Consumes: the future `ada run test`, `ada artifact read`, and analysis CLI JSON contracts.
- Produces: canonical client-independent Skill and a thin OpenCode tool whose result is unmodified CLI JSON.

- [ ] **Step 1: Add a deterministic asset validation script invocation**

Validation commands:

```powershell
rg -n "^name: algorithm-debug$|TARGET_TEST_RUN_COMPLETED|latestRunForAnalysis|artifact" skills/algorithm-debug/SKILL.md
rg -n "ada (run test|artifact read|analysis)" integrations/opencode
```

Expected before creation: FAIL because canonical assets do not exist.

- [ ] **Step 2: Write the canonical Skill**

The Skill must instruct the model to: identify `eventType`; prefer the latest run only when `latestRunForAnalysis=true`; distinguish target failure from Agent failure; reuse historical immutable evidence when sufficient; read bounded raw excerpts by artifact reference; rerun only when needed; compare changed Gantt/context explicitly; request CodePath/JDWP only for a stated evidence gap; grade conclusions by evidence class.

- [ ] **Step 3: Write the thin OpenCode tool and fallback agent/command**

Define typed arguments for `analysis-begin`, `run-test`, `artifact-read`, and `analysis-complete`. The tool invokes `ada` with `context.directory` as project path, captures stdout, and returns it verbatim; nonzero exits return structured stderr without interpreting exceptions. The fallback agent and command load the canonical algorithm-debug Skill.

- [ ] **Step 4: Validate assets and legacy markers**

Run the two `rg` commands from Step 1 plus `rg -n "legacy|canonical" .opencode distribution integrations/opencode`.
Expected: canonical content exists; `.opencode` is explicitly legacy and contains no divergent workflow rules.

- [ ] **Step 5: Commit Task 3**

Commit message: `feat: add canonical algorithm debug skill`.

### Task 4: Cross-module documentation, compatibility audit, and repository verification

**Files:**
- Modify: `docs/designs/2026-08-12-case-context-run-outcome-multiturn-analysis-design.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/architecture/algorithm-debug-agent-complete-design.md`
- Modify: `docs/decisions/ADR-006-case-as-analysis-dossier.md`
- Create: `docs/decisions/ADR-007-opencode-adapter-via-cli.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-3 concrete names and limitations.
- Produces: one consistent repository-level design baseline and explicit migration boundary.

- [ ] **Step 1: Check design/code naming consistency**

Run: `rg -n "failureKind|nextAllowedActions|algorithm-debug-mcp|inquiryId|turnId" README.md docs ada-contracts debug-harness skills integrations .opencode`
Expected: matches are limited to superseded history or explicit migration notes.

- [ ] **Step 2: Check formatting and changed-file scope**

Run: `git diff --check` and `git status --short`.
Expected: no whitespace errors; only files named by this plan are changed.

- [ ] **Step 3: Run the full repository test suite**

Run: `mvn test`
Expected: all modules PASS; no target-repository/network dependency is required by unit tests.

- [ ] **Step 4: Review failures before fixing**

If a test fails, use `superpowers:systematic-debugging`: reproduce the smallest failing module, identify the first causal failure, add or retain a regression assertion, apply the smallest in-scope fix, then rerun the module and root suites.

- [ ] **Step 5: Commit the synchronized baseline**

Commit message: `docs: align run outcome and opencode integration`.

## Self-Review

- Spec coverage: the three-part result is covered by Tasks 1 and 3; generic exception facts and failed Gantt are covered by Task 2; repository-owned Skill/OpenCode assets and no-MCP boundary are covered by Tasks 3 and 4.
- Placeholder scan: no `TBD`, `TODO`, “implement later”, or unspecified error-handling step remains.
- Type consistency: `ContextId`、`AnalysisId`、`RunId`、`FailureCategory`、`TargetFailureDiagnostic` and `RunOutcomeSummary` names are identical across tasks.
- Scope boundary: a working `ada install opencode` cannot be completed safely until the CLI vertical slice exists and the supported OpenCode version is pinned. This plan therefore creates the canonical assets and callable tool contract, while the installer implementation remains a separately reviewable follow-up module rather than a fake installer.

## Implementation Record

- 2026-08-12：Tasks 1-4 已按 Red-Green-Refactor 完成；新增 JSON round-trip 和 XML stderr 污染回归测试。
- 受影响模块及根项目 `mvn test` 通过；两个依赖外部真实示例工程的 Smoke Test 按既有条件跳过。
- 本机 OpenCode 运行时加载验证被用户全局配置目录权限异常阻断；未修改全局配置。
- `ada install opencode` 保持为下一独立 CLI 垂直切片，必须包含版本检测、幂等注册、回滚和临时目录测试。
