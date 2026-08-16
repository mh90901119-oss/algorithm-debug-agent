# Run Outcome 审计修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Run Outcome、Case/Context 边界、Surefire 诊断和 OpenCode 薄适配的已确认审计问题，使目标 UT 失败始终作为可分析事实返回，并让仓库状态与实际可运行能力一致。

**Architecture:** `debug-harness` 在取得 `RunResult` 后不得再用采集异常遮蔽目标运行事实；`case-management` 直接采用 Case/Context/Analysis 术语，不保留 Revision/Inquiry 工作流；`ada-contracts` 和 JSON Schema 共同约束正交结果维度；OpenCode 层只做有界进程 I/O 和 ToolResponse 2.0 校验，CLI 与安装器仍是下一独立垂直切片。

**Tech Stack:** Java 21、Maven、JUnit 5、JDK DOM XML Parser、JSON Schema Draft 2020-12、Node.js built-in test runner、OpenCode TypeScript Custom Tool。

## Global Constraints

- 不穷举 Java 或业务异常；确定性代码只提取执行阶段、异常类、规范化消息、最深 cause 和稳定业务栈帧。
- 目标 UT 结果、Gantt 可用性和 Agent 采集失败保持正交；取得 `RunResult` 后必须返回组合事实。
- 不在本修复中实现 `ada` CLI、OpenCode 安装器、Case Repository 或 Run 持久化编排。
- 不宣称 OpenCode 集成已经可日常运行；仓库适配资产在 CLI 与安装器完成前属于已验证契约资产。
- 每个行为变更先写失败测试并确认 RED，再做最小实现并确认 GREEN。

---

### Task 1: 保留目标运行事实并表达不完整 Gantt

**Files:**
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleRunResult.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunner.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`
- Modify: `debug-harness/README.md`

**Interfaces:**
- Produces: `ScheduleRunResult` 增加 `GanttOutcome` 与可选 `AgentFailureDiagnostic`，并校验 `PRESENT/ABSENT/INCOMPLETE` 组合。
- Preserves: `run()` 在运行前快照或进程启动失败时仍抛 `HarnessException`；一旦取得 `RunResult`，后续 Gantt 扫描、稳定等待、解析、复制或哈希失败都返回组合结果和本轮变化候选路径。

- [x] **Step 1: Write the failing regression tests**

新增用例：失败 UT 写出不可解析文件时，`runner.run()` 不抛异常，返回原 `RunCompletion.FAILED`、`GanttOutcome.INCOMPLETE`、空 `scheduleResult` 和 `HARNESS_RESULT_NOT_PRODUCED` Agent 诊断；无输出返回 `ABSENT`；成功捕获返回 `PRESENT`。

- [x] **Step 2: Run the focused test to verify RED**

Run: `mvn -pl debug-harness -am '-Dtest=ScheduleProducingTestRunnerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test`

Expected: FAIL because `ScheduleRunResult` does not expose Gantt outcome or capture failure and the runner throws.

- [x] **Step 3: Implement the minimal orthogonal result model**

在 `executor.execute` 返回后包围全部 Gantt 后处理，将 `HarnessException.code()` 转成有界、无路径泄漏的 `AgentFailureDiagnostic`；不吞掉运行前或进程执行异常。

- [x] **Step 4: Run module tests to verify GREEN**

Run: `mvn -pl debug-harness -am test`

Expected: PASS.

### Task 2: 把旧 Case Revision/Inquiry 模型迁移为 Context/Analysis

**Files:**
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseResolution*.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseIntent.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/ManagedCase.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseWorkspace.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/BaselineStabilityService.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BaselineStabilityState.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseLifecycleState.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InquiryId.java`
- Delete: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/TurnId.java`
- Modify: affected unit and integration tests

**Interfaces:**
- Produces: `CaseResolutionAction = NEW_CASE | REUSE_CONTEXT | NEW_CONTEXT` and `CaseIntent = AUTO | FORCE_NEW_CASE | FORCE_NEW_CONTEXT`.
- Preserves: `BaselineVerification v1` JSON enum values remain `BASELINE_CANDIDATE/STABLE/UNSTABLE`; only the Java type stops pretending to be the Case workflow state.

- [x] **Step 1: Write failing Case and workspace tests**

断言同一 UT 且 Fingerprint 变化返回 `NEW_CONTEXT`，相同 Fingerprint 返回 `REUSE_CONTEXT`，目标 UT 变化返回 `NEW_CASE`；新 workspace 创建 `contexts/runs/analyses/evidence` 且不创建 `inquiries`。

- [x] **Step 2: Run Case tests to verify RED**

Run: `mvn -pl case-management -am test`

Expected: FAIL on missing Context actions and directories.

- [x] **Step 3: Implement migration and baseline type split**

删除未被任何生产代码引用的预发布 Inquiry/Turn ID；去除 Revision parent 关系；使用 `BaselineStabilityState` 保持既有 JSON 字面值和稳定性算法。

- [x] **Step 4: Run affected tests to verify GREEN**

Run: `mvn -pl case-management,integration-tests -am test`

Expected: PASS, with real external-project smoke tests remaining conditionally skipped.

### Task 3: 强化 RunOutcome 与 Surefire 确定性契约

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunOutcomeSummary.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/FailureCategory.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunOutcomeSummaryTest.java`
- Modify: `schemas/execution/run-outcome-summary-v1.schema.json`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireDiagnosticReader.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/SurefireDiagnosticReaderTest.java`

**Interfaces:**
- Produces: `PASSED` 禁止 target failure，`FAILED/ERROR` 必须带匹配分类，`PRESENT` 必须引用 `GANTT` Artifact；目标分类不再包含 `AGENT_FAILURE`。
- Produces: Surefire reader 只解析目标测试类报告，支持参数化方法名、最深 cause、命名空间安全和 10 MiB 默认文件预算。

- [x] **Step 1: Write failing contract and parser tests**

新增矛盾组合拒绝、参数化测试名、嵌套 cause、无关损坏 XML 不遮蔽目标报告和超预算拒绝用例。

- [x] **Step 2: Run focused tests to verify RED**

Run: `mvn -pl ada-contracts,debug-harness -am test`

Expected: FAIL on the new invariants and parser cases.

- [x] **Step 3: Implement minimal validation and bounded parsing**

Java record 与 JSON Schema 使用相同条件约束；XML Factory 开启 namespace aware 并继续禁用 DTD/外部实体；只读取标准 `TEST-<class>.xml`。

- [x] **Step 4: Run affected tests to verify GREEN**

Run: `mvn -pl ada-contracts,debug-harness -am test`

Expected: PASS.

### Task 4: 让 OpenCode 薄适配安全且不夸大可运行状态

**Files:**
- Create: `integrations/opencode/lib/ada-cli.mjs`
- Create: `integrations/opencode/test/ada-cli.test.mjs`
- Modify: `integrations/opencode/tools/algorithm-debug.ts`
- Create: `schemas/tool/tool-response-v2.schema.json`
- Modify: `integrations/opencode/README.md`
- Modify: `docs/decisions/ADR-007-opencode-adapter-via-cli.md`

**Interfaces:**
- Produces: `runAdaCommand(args, cwd, spawn, options)`，最多读取 stdout/stderr 各 1 MiB，默认总运行预算 15 分钟，只原样返回通过 ToolResponse 2.0 校验的 stdout。
- Failure behavior: 启动失败、输出超限、非 JSON 或协议不符时返回本地构造的 ToolResponse 2.0 失败，不把原始日志回显给模型。

- [x] **Step 1: Write failing Node contract tests**

覆盖合法响应原样返回、spawn 异常、超限输出、非法 JSON、错误 schemaVersion 和非法 Artifact。

- [x] **Step 2: Run tests to verify RED**

Run: `node --test integrations/opencode/test/ada-cli.test.mjs`

Expected: FAIL because the library does not exist.

- [x] **Step 3: Implement bounded adapter and wire the Custom Tool**

适配层不解释 Maven/UT 异常；stderr 只被有界消费，不进入模型响应；TypeScript 工具通过注入 `Bun.spawn` 调用纯适配库。

- [x] **Step 4: Run Node tests and syntax checks**

Run: `node --test integrations/opencode/test/ada-cli.test.mjs`

Run: `node --check integrations/opencode/lib/ada-cli.mjs`

Expected: PASS.

### Task 5: 文档同步、全仓验证与复审

**Files:**
- Modify: `docs/designs/2026-08-12-case-context-run-outcome-multiturn-analysis-design.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/decisions/ADR-006-case-as-analysis-dossier.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify: module/root README files and Schema index

- [x] **Step 1: Synchronize status language**

把“已解决/可使用”区分为“已实现并验证”“部分实现”“待 CLI/安装器”；明确 OpenCode 外部目录发现机制要由下一模块按锁定版本实测，不能仅依据模板假设。

- [x] **Step 2: Run repository checks**

Run: `git diff --check`

Run: `mvn test`

Run: `node --test integrations/opencode/test/ada-cli.test.mjs`

Expected: all pass; only the two existing external-demo tests may be skipped.

- [x] **Step 3: Audit implementation against the approved architecture**

检查：无 Revision/Inquiry 工作流生产引用；目标与 Agent 失败不混淆；所有新增输出有界；OpenCode 文档不宣称缺失 CLI 已可调用；未引入 MCP 或客户端无关代码对 OpenCode 的反向依赖。

## Self-Review

- Spec coverage: 审计的四类代码问题及文档状态偏差均有独立任务和验证命令。
- Placeholder scan: 没有 TBD/TODO 或未定义接口；真正 CLI/安装器被明确列为非目标和下一模块。
- Type consistency: `NEW_CONTEXT`、`REUSE_CONTEXT`、`BaselineStabilityState`、`GanttOutcome` 与 `AgentFailureDiagnostic` 在任务间一致。
- Compatibility: BaselineVerification v1 的 JSON 字面值不变；其他删除/重命名类型均为未发布且无生产引用的 Phase 0 API，变更记录必须明确。

## Implementation Record

- 2026-08-13：按 Red-Green-Refactor 完成全部任务；最终全仓 `mvn test` 为 87 tests、0 failures、
  0 errors、2 个外部环境 Smoke skipped。
- OpenCode 适配单测 11/11 通过，覆盖进程启动失败、流读取失败、超时、输出预算、严格响应字段、
  Artifact ID/路径/Hash 契约和非零退出码下的合法 CLI 失败响应。
- 两个本轮修改的 Schema 与既有 Baseline Manifest Schema 均通过严格 JSON 解析；`git diff --check`
  无空白错误，仅报告 Windows 工作树的 LF/CRLF 转换提示。
- 最终复审未发现架构边界偏离；CLI、Repository 与安装器仍未实现，没有在文档中宣称可日常使用。
