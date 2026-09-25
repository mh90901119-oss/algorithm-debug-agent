# Portable MCP Algorithm Debug Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将当前 OpenCode 专用的 13 个工具入口重构为 Java 原生、宿主无关、带确定性 Coordinator 的 MCP Algorithm Debug 子 Agent，并首先交付可安装的 Qwen CLI Extension。

**Architecture:** `algorithm-debug-runtime` 是 CLI 与 MCP 共用的唯一生产组合根；`algorithm-debug-mcp-server` 只实现 stdio MCP 协议、Catalog 和结果映射。所有模型动作都经 `ada-core` 的 `AnalysisCoordinator`，Coordinator 从追加式 Case 归档投影状态并执行 Policy、幂等、跨进程锁、证据义务和结论门禁。

**Tech Stack:** Java 21、Maven 3.9+、JUnit 5、Jackson 2.17.2、官方 MCP Java SDK 2.0.1、JSON Schema Draft 2020-12、PowerShell、Qwen CLI Extension。

**Spec:** `docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md` 0.3、`docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`

**Execution method:** Native；在当前根仓库逐任务执行，不使用 `.worktrees`，每个任务独立 Red-Green-Refactor 并提交。

## Global Constraints

- Java 固定为 21；MCP SDK 固定为 `io.modelcontextprotocol.sdk:mcp-core:2.0.1` 与 `mcp-json-jackson2:2.0.1`，不引入 Spring 或 Jackson 3。
- `ada-contracts` 不依赖实现模块；`algorithm-debug-runtime` 可依赖具体 Adapter/Collector，但不得依赖 CLI、MCP Server 或宿主 Adapter。
- MCP Server 不调用模型，不保存模型凭据，不实现第二套 Agent Loop；宿主创建模型子 Agent。
- 当前根仓库第一版 Catalog 固定为现有 13 个工具加 `analysis_status`、`analysis_finalize`；数量通过 Catalog 计算，不写成长久在线协议常量。
- 13/18 工具差异中未知的 5 个工具不得推测实现；取得真实名称、Schema、实现和回归用例后另增任务。
- Case、Operation、Coordination、Conclusion 和 Evidence 只追加；不保存可覆盖的 `current-state.json`，不重写历史 Case。
- 动态采集不得使用 Gantt SHA 作为通用门禁；成功 UT 不要求失败基准，失败 UT 只有结构化指纹 `MATCHED` 才可确认同类失败。
- `evidenceUsable` 只保留为旧 JSON 兼容别名；新代码不得用它同时表达可读性、完整性、失败复现和结论资格。
- `run_test`、`codepath_collect`、`jdwp_collect` 对同一 Project 获取 OS 文件锁；等待期间状态变化时必须重新投影再授权。
- MCP stdout 只能包含协议帧；日志写 stderr/DFX 文件。请求、响应、Artifact 数、对象深度、线程、时间和关闭等待全部有界。
- 现有 `config/agent-settings.json` 和 OpenCode 资产在迁移窗口保留；MCP 使用新增 `config/mcp-agent-settings.json`。
- 用户现有未跟踪目录 `docs/sharing/` 不修改、不暂存、不提交。

## Review Focus

1. 成功 CodePath/JDWP 首次采集且没有普通 Run：必须保存并返回数据，`baselineRequired=false`，不能被旧 `evidenceUsable=false` 丢弃；Task 2 的两个服务回归测试固定该行为。
2. 两个独立 MCP Server 同时执行同一 Project：只能有一个启动目标 JVM，另一个返回 `TARGET_EXECUTION_BUSY`；Task 5 单元测试和 Task 15 双进程集成测试固定该行为。
3. operation 同时存在两个终态或文档损坏：State Projector 返回结构化冲突，不选择“最后一个”；Task 4 与 Task 6 固定该行为。
4. Server、SDK 或依赖库把日志写入 stdout：stdio 客户端必须检测协议污染并失败，生产日志只写 stderr；Task 11 契约测试固定该行为。
5. 历史 Collection 没有 `eligibility` 字段：仍可读取，但投影为 `LEGACY_UNKNOWN`，不能自动获得确认资格；Task 2 兼容测试和 Task 6 状态测试固定该行为。

---

## File Structure

| 路径 | 单一职责 |
|---|---|
| `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceEligibility.java` | 数据可读性、完整性、基准要求、复现和结论资格的正交事实 |
| `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/` | Action、ControlView、Operation、Obligation、Conclusion 和统一结果契约 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/OperationJournal.java` | 追加写 operation STARTED 与唯一终态 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLock.java` | Project 级跨进程目标执行锁 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisStateRepository.java` | 有界读取状态投影所需的已校验归档 |
| `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjector.java` | 从归档生成确定性 `AnalysisControlView` |
| `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinator.java` | 唯一授权、锁、幂等、执行和后置校验管线 |
| `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionRegistry.java` | Action 到 typed Policy/Handler 的不可变注册 |
| `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrap.java` | CLI/MCP 共用的唯一生产组合根 |
| `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolCatalog.java` | 15 个 Tool 的唯一名称、Schema、注解和 Action 映射 |
| `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolDispatcher.java` | 所有 `tools/call` 的唯一入口 |
| `agent-definition/*` | 宿主无关 Agent 身份、Prompt、能力和完成契约 |
| `integrations/qwen-cli/*` | Qwen Extension、子 Agent Profile 与安装/检查/卸载脚本 |

### Task 1: 冻结实施基线与依赖事实

**Files:**
- Create: `docs/development/mcp-implementation-baseline.md`
- Create: `docs/development/host-capability-dependency-inventory.md`
- Modify: `docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md`
- Modify: `docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`
- Modify: `docs/audits/2026-09-25-portable-mcp-subagent-design-audit.md`

**Interfaces:**
- Produces: 已批准设计状态、MCP 依赖解析证据、本地 13 工具清单、旧调用链测试基线。
- Consumes: 设计 0.3、ADR-018、当前 Maven/Java 环境。

- [ ] **Step 1: 将批准状态写入设计和 ADR**

设计头改为 `文档状态：Approved` 并记录批准日期；ADR 改为 `Accepted`。审计结论改为
`IMPLEMENTATION_AUTHORIZED`，但保留 SDK、工具清单和宿主能力发布门禁。

- [ ] **Step 2: 记录确定的依赖和环境基线**

`mcp-implementation-baseline.md` 必须记录 Java 21.0.11、Maven 3.9.16、两个 SDK Artifact 2.0.1 的解析结果、
Maven Central 坐标、MIT 许可证、Jackson 2 选择、当前 commit 和以下 13 个工具：

```text
analysis_begin case_inspect algorithm_input_capture case_audit gantt_inspect
run_test static_analyze codepath_plan_create codepath_collect
jdwp_plan_create jdwp_collect artifact_read evidence_query
```

`host-capability-dependency-inventory.md` 逐条引用当前 Agent/Skill 中的工具要求，记录当前工作流明确禁止 bash 和宿主
source-search/read，并要求源码规划统一通过 `static_analyze`、Artifact 读取通过 `artifact_read/evidence_query`。因此
第一版不存在必须补建的宿主源码 Tool；若后续 Prompt/Eval 引入该依赖，必须先修改此清单和设计。

- [ ] **Step 3: 重新执行依赖解析和现状测试**

Run:

```powershell
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-core:2.0.1"
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1"
mvn -pl ada-contracts,case-management,evidence-engine,ada-core,algorithm-debug-cli -am test
```

Expected: 两个依赖解析成功；现有模块测试全绿。失败时只记录真实失败并停止，不进入生产代码。

- [ ] **Step 4: Commit**

```powershell
git add docs/development/mcp-implementation-baseline.md docs/development/host-capability-dependency-inventory.md docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md docs/decisions/ADR-018-java-native-mcp-portable-subagent.md docs/audits/2026-09-25-portable-mcp-subagent-design-audit.md
git commit -m "docs: approve portable mcp implementation baseline"
```

### Task 2: 拆分动态证据资格语义并修复成功 UT

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceEligibility.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionBaselineCheck.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionExecutionSummary.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/collection/evidence-eligibility-v1.schema.json`
- Create: `schemas/collection/collection-execution-summary-v3.schema.json`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceEligibilityEvaluator.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceEligibilityEvaluatorTest.java`
- Modify: `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Modify: `trace-validator/src/test/java/org/example/algorithmdebug/validator/CollectionEvidenceValidatorTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/RegisteredEvidenceQuery.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/RegisteredEvidenceQueryTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/JdwpCollectionApplicationServiceTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/EvidenceEligibilityJsonTest.java`

**Interfaces:**
- Produces: `EvidenceEligibility` 1.0；`CollectionExecutionSummary.eligibility()`；旧 `evidenceUsable()` 仅返回 `confirmationEligible`。
- Consumes: Collection completion、事件/命中数、截断事实、`ComparisonOutcome`、Normalizer/Validator 结果。

核心契约必须是：

```java
public record EvidenceEligibility(
        String schemaVersion,
        boolean artifactReadable,
        boolean collectionComplete,
        boolean baselineRequired,
        boolean baselineComparable,
        boolean failureFingerprintMatched,
        boolean obligationSatisfied,
        boolean confirmationEligible,
        List<String> reasonCodes) {
}
```

Evaluator 接口固定为：

```java
public final class EvidenceEligibilityEvaluator {
    public EvidenceEligibility evaluate(Context context) {
        Objects.requireNonNull(context, "context");
        if (context.retainedEvidenceCount() < 0) {
            throw new IllegalArgumentException("retainedEvidenceCount must not be negative");
        }
        boolean baselineRequired = context.targetFailed();
        boolean baselineComparable = baselineRequired
                && (context.baselineOutcome() == ComparisonOutcome.MATCHED
                || context.baselineOutcome() == ComparisonOutcome.CHANGED);
        boolean fingerprintMatched = baselineRequired
                && context.baselineOutcome() == ComparisonOutcome.MATCHED;
        boolean obligationSatisfied = context.retainedEvidenceCount() > 0
                && context.validationSatisfied();
        boolean confirmationEligible = context.artifactReadable()
                && context.collectionComplete()
                && obligationSatisfied
                && (!baselineRequired || fingerprintMatched);
        ArrayList<String> reasons = new ArrayList<>(context.limitationCodes());
        if (!context.artifactReadable()) reasons.add("ARTIFACT_UNREADABLE");
        if (!context.collectionComplete()) reasons.add("COLLECTION_INCOMPLETE");
        if (context.retainedEvidenceCount() == 0) reasons.add("NO_EVIDENCE_HITS");
        if (baselineRequired && !fingerprintMatched) reasons.add("FAILURE_NOT_REPRODUCED");
        return new EvidenceEligibility(
                SchemaVersions.EVIDENCE_ELIGIBILITY,
                context.artifactReadable(), context.collectionComplete(),
                baselineRequired, baselineComparable, fingerprintMatched,
                obligationSatisfied, confirmationEligible, List.copyOf(reasons));
    }

    public record Context(
            boolean artifactReadable,
            boolean collectionComplete,
            boolean targetFailed,
            long retainedEvidenceCount,
            ComparisonOutcome baselineOutcome,
            boolean validationSatisfied,
            List<String> limitationCodes) { }
}
```

规则固定为：成功时 `baselineRequired=false`；失败时 `baselineRequired=true`；失败且非 `MATCHED` 时
`confirmationEligible=false`；无命中使 `obligationSatisfied=false`；数据仍通过 Artifact 引用保留。

- [ ] **Step 1: 写 evaluator 和服务回归失败测试**

新增测试方法：

```java
@Test void passingCollectionWithoutReferenceRunDoesNotRequireFailureBaseline() { }
@Test void failedCollectionRequiresMatchedFailureFingerprint() { }
@Test void changedFailureRemainsReadableButCannotConfirm() { }
@Test void zeroHitCollectionFailsObligationWithoutBecomingUnreadable() { }
@Test void truncatedCollectionRemainsReadableButIncomplete() { }
```

在两个 ApplicationService 测试中各增加 `successfulCollectionWithoutRunTestIsNotDiscarded`，断言
`baselineRequired=false`、`artifactReadable=true`，且 primary Artifact 仍注册。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
mvn -pl case-management,trace-validator,evidence-engine,ada-core -am test "-Dtest=EvidenceEligibilityEvaluatorTest,CollectionEvidenceValidatorTest,RegisteredEvidenceQueryTest,CollectionApplicationServiceTest,JdwpCollectionApplicationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL，因为契约/evaluator 不存在，且当前服务会把无普通 Run 的成功采集判为不可用。

- [ ] **Step 3: 实现最小正交资格计算**

`CollectionBaselineCheck.evidenceUsable` 保留用于旧 JSON 读取，并标记 `@Deprecated`；新增语义单一的方法
`baselineGateSatisfied()`。成功 Collection 无论是否存在普通 Run 都写 `NOT_COMPARED + true`；失败 Collection 只有
`MATCHED + true`。Validator 改读 `baselineGateSatisfied()`，所有其他生产代码改为调用 evaluator 或
`summary.eligibility()`，不再直接读取 `evidenceUsable()`。

- [ ] **Step 4: 保持历史 JSON 可读**

`CollectionExecutionSummary` 新增 `schemaVersion` 和末尾 `EvidenceEligibility eligibility`；生产写入显式使用
`SchemaVersions.COLLECTION_EXECUTION_SUMMARY = "3.0"`。旧构造器保留；Jackson 读取没有 `schemaVersion/eligibility`
的历史 JSON 时按 v2 兼容路径创建 `LEGACY_UNKNOWN` eligibility，`confirmationEligible=false`，不从旧布尔自动升级
确认资格。v2 Schema 保留不改，新写入用 v3 Schema 测试。

- [ ] **Step 5: 运行模块回归并 Commit**

```powershell
mvn -pl ada-contracts,case-management,trace-validator,evidence-engine,ada-core -am test
git add ada-contracts case-management trace-validator evidence-engine ada-core schemas/collection
git commit -m "fix: separate collection evidence eligibility"
```

### Task 3: 建立 Coordinator 公共契约与 Schema

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisActionType.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionOutcome.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionDecision.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionDecisionCode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionSideEffect.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionTarget.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/OperationId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/OperationState.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/OperationReceipt.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/EvidenceObligationKind.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ObligationStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/EvidenceObligation.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/TerminalEligibility.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisControlView.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionCandidate.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionClaim.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionDecision.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/CoordinatedToolResult.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/CoordinationChecks.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/coordination/action-decision-v1.schema.json`
- Create: `schemas/coordination/action-target-v1.schema.json`
- Create: `schemas/coordination/operation-receipt-v1.schema.json`
- Create: `schemas/coordination/evidence-obligation-v1.schema.json`
- Create: `schemas/coordination/analysis-control-view-v1.schema.json`
- Create: `schemas/coordination/conclusion-candidate-v1.schema.json`
- Create: `schemas/coordination/conclusion-decision-v1.schema.json`
- Create: `schemas/tool/coordinated-tool-result-v1.schema.json`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationContractsTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationJsonTest.java`

**Interfaces:**
- Produces: 所有后续模块唯一使用的版本化协调 DTO。
- Consumes: 现有 `ProjectId`、`CaseId`、`AnalysisId`、`ArtifactReference`、`ClaimClassification`。

15 个 Action 枚举值固定为：

```java
ANALYSIS_BEGIN, CASE_INSPECT, ALGORITHM_INPUT_CAPTURE, CASE_AUDIT, GANTT_INSPECT,
RUN_TEST, STATIC_ANALYZE, CODEPATH_PLAN_CREATE, CODEPATH_COLLECT,
JDWP_PLAN_CREATE, JDWP_COLLECT, ARTIFACT_READ, EVIDENCE_QUERY,
ANALYSIS_STATUS, ANALYSIS_FINALIZE
```

Action 目标和副作用契约固定为：

```java
public enum ActionSideEffect {
    READ_ONLY, CASE_WRITE, TARGET_EXECUTION
}

public record ActionTarget(
        ProjectId projectId,
        Optional<CaseId> caseId,
        Optional<AnalysisId> analysisId) {
    public ActionTarget {
        Objects.requireNonNull(projectId, "projectId");
        caseId = Objects.requireNonNull(caseId, "caseId");
        analysisId = Objects.requireNonNull(analysisId, "analysisId");
        if (analysisId.isPresent() && caseId.isEmpty()) {
            throw new IllegalArgumentException("analysisId requires caseId");
        }
    }
}
```

统一结果签名固定为：

```java
public record CoordinatedToolResult<T>(
        String schemaVersion,
        ActionOutcome outcome,
        String code,
        String message,
        Optional<T> data,
        List<ArtifactReference> artifacts,
        AnalysisControlView control) {
}
```

控制视图签名固定为；Case/Analysis 在 `ANALYSIS_BEGIN` 的前置视图中允许为空，动作完成后的视图必须带新身份：

```java
public record AnalysisControlView(
        String schemaVersion,
        ProjectId projectId,
        Optional<CaseId> caseId,
        Optional<AnalysisId> analysisId,
        long revision,
        String stateSha256,
        Set<AnalysisActionType> completedActions,
        List<EvidenceObligation> obligations,
        List<String> contradictionIds,
        List<AnalysisActionType> allowedActions,
        TerminalEligibility terminalEligibility) {
}

public record ActionDecision(
        boolean allowed,
        List<ActionDecisionCode> reasonCodes,
        String message) {
}
```

- [ ] **Step 1: 写不可变性、边界和 Schema 失败测试**

覆盖未知版本、`SUCCEEDED` 无 data、`REJECTED` 带副作用结果、跨 Case Evidence 引用、超过 64 个义务、超过
32 个 reason code、确认结论缺 Evidence ID、`CONFIRMED_FACT` 与 `TerminalEligibility.HYPOTHESIS_ONLY` 冲突。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl ada-contracts -am test "-Dtest=CoordinationContractsTest,CoordinationJsonTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现契约、中文 Javadoc 和 Schema**

`AnalysisControlView` 必须包含 `caseId`、可选 `analysisId`、`revision`、`stateSha256`、已完成动作、义务、矛盾、
允许动作和 `terminalEligibility`；不得包含完整 Raw Trace、Gantt 或绝对 Artifact 路径。

- [ ] **Step 4: 运行契约测试并 Commit**

```powershell
mvn -pl ada-contracts -am test
git add ada-contracts schemas/coordination schemas/tool/coordinated-tool-result-v1.schema.json
git commit -m "feat: define coordination contracts"
```

### Task 4: 实现追加式 Operation 与决策归档

**Files:**
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/OperationJournal.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CoordinationDecisionArchive.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ConclusionDecisionArchive.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/OperationJournalTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/CoordinationDecisionArchiveTest.java`

**Interfaces:**
- Produces: `start(ActionTarget, OperationId, AnalysisActionType, String)`、
  `complete(ActionTarget, OperationId, String)`、`fail(ActionTarget, OperationId, String)`、
  `markUncertain(ActionTarget, OperationId, String)`、`find(ActionTarget, OperationId)`；所有写入只创建新文件。
- Consumes: `AtomicDocumentWriter`、`BoundedDocumentMapper`、Task 3 Operation/Decision DTO。

固定路径 API：

```java
public Path operationRoot(AnalysisId analysisId, OperationId operationId);
public Path operationStarted(AnalysisId analysisId, OperationId operationId);
public Path operationTerminal(AnalysisId analysisId, OperationId operationId, OperationState state);
public Path coordinationDecision(AnalysisId analysisId, String decisionId);
public Path conclusionRoot(AnalysisId analysisId, String conclusionId);
```

- [ ] **Step 1: 写追加、冲突和崩溃恢复失败测试**

测试 STARTED 重复请求返回已有 receipt；COMPLETED 后禁止 FAILED；同时存在两个终态时读取返回
`OPERATION_JOURNAL_CONFLICT`；只有 STARTED 时返回 `UNCERTAIN` 视图而不自动执行。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl case-management -am test "-Dtest=OperationJournalTest,CoordinationDecisionArchiveTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 使用 AtomicDocumentWriter 实现唯一终态**

终态写入前列举 `completed.json`、`failed.json`、`uncertain.json`；存在任一终态即拒绝第二终态。读取按文件名确定
状态，不依赖 mtime。所有 JSON 限制 256 KiB，未知字段和哈希失败结构化报错。

- [ ] **Step 4: 运行并 Commit**

```powershell
mvn -pl case-management -am test
git add case-management
git commit -m "feat: archive coordination operations"
```

### Task 5: 实现跨进程目标执行锁

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLock.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLockManager.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLockTest.java`

**Interfaces:**
- Produces: `Optional<WorkspaceExecutionLock> tryAcquire(Path workspaceRoot, ProjectId projectId, Duration timeout)`。
- Consumes: `WorkspaceLayout` 和 `projects/{projectId}/.control/target-execution.lock`。

接口固定为：

```java
public interface WorkspaceExecutionLock extends AutoCloseable {
    String lockKey();
    @Override void close();
}
```

- [ ] **Step 1: 写同 Project 竞争、不同 Project 并行、超时和异常释放测试**

测试两个独立 Manager 竞争同一文件；第一个 close 后第二个可获得；不同 projectId 不互斥；符号链接/重解析点
或逃逸路径被拒绝；中断等待返回 `TARGET_EXECUTION_CANCELLED`。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl case-management -am test "-Dtest=WorkspaceExecutionLockTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 用 FileChannel.tryLock 实现有界等待**

捕获 `OverlappingFileLockException` 并按固定 25 ms Clock/Sleeper 端口重试；测试注入 Sleeper，生产默认最大等待
5 秒。`close()` 幂等释放 FileLock 和 Channel，保留原始 cause。

- [ ] **Step 4: 运行并 Commit**

```powershell
mvn -pl case-management -am test
git add case-management
git commit -m "feat: serialize target executions across processes"
```

### Task 6: 从不可变归档投影分析状态

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisArtifactIndex.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisArchiveSnapshot.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ProjectionLimits.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisStateRepository.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/AnalysisStateRepositoryTest.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjector.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjectorTest.java`

**Interfaces:**
- Produces: `AnalysisArchiveSnapshot read(ActionTarget target, ProjectionLimits limits)` 和
  `AnalysisControlView project(ActionTarget target)`。
- Consumes: Case/Analysis/Run/Plan/Collection/Evidence/Operation/Conclusion 文档及 Artifact 注册。

限制固定为：

```java
public record ProjectionLimits(int maxArtifacts, long maxBytes, Duration timeout) {
    public static ProjectionLimits defaults() {
        return new ProjectionLimits(10_000, 64L * 1024 * 1024, Duration.ofSeconds(5));
    }
}
```

- [ ] **Step 1: 写确定性、历史兼容和损坏输入失败测试**

相同文档不同目录枚举顺序必须得到相同 `stateSha256`；历史 Collection 无 eligibility 时生成
`LEGACY_UNKNOWN`；双终态、跨 Analysis Plan、Artifact 哈希错误、超过 10,000 Artifact 和超时分别返回稳定错误码。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl case-management,ada-core -am test "-Dtest=AnalysisStateRepositoryTest,AnalysisStateProjectorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现排序、校验、哈希和允许动作计算**

只读取 Manifest/摘要/注册文档，不读取 Raw Trace；输入按 `relativePath + artifactId` 排序后计算 SHA-256；
`projectedAt` 不进入 state hash。允许动作由事实推导，不使用可变内存 session。

- [ ] **Step 4: 运行并 Commit**

```powershell
mvn -pl case-management,ada-core -am test
git add case-management ada-core
git commit -m "feat: project analysis control state"
```

### Task 7: 实现不可绕过的 Coordinator 执行管线

**Files:**
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AgentRequestContext.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionRequest.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionPolicy.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionHandler.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionBinding.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionRegistry.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinator.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyService.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinatorTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyServiceTest.java`

**Interfaces:**
- Produces: `public <I,O> CoordinatedToolResult<O> execute(AnalysisActionRequest<I> request)`。
- Consumes: StateProjector、Registry、OperationJournal、WorkspaceExecutionLockManager、Clock。

泛型边界固定为：

```java
public record AgentRequestContext(
        Path workspaceRoot,
        String invocationId,
        BooleanSupplier cancellationRequested) {
}

public record AnalysisActionRequest<I>(
        AnalysisActionType actionType,
        ActionSideEffect sideEffect,
        ActionTarget target,
        Optional<OperationId> operationId,
        AgentRequestContext requestContext,
        I input) {
}

public interface AnalysisActionPolicy<I, O> {
    ActionDecision authorize(AnalysisControlView before, AnalysisActionRequest<I> request);
    ActionDecision verify(AnalysisControlView before, O result, AnalysisControlView after);
}

public interface AnalysisActionHandler<I, O> {
    O execute(AgentRequestContext context, I input);
}

public record AnalysisActionBinding<I, O>(
        AnalysisActionType actionType,
        ActionSideEffect sideEffect,
        Class<I> inputType,
        AnalysisActionPolicy<I, O> policy,
        AnalysisActionHandler<I, O> handler) {
}
```

- [ ] **Step 1: 写执行顺序和拒绝无副作用测试**

用 fake Projector/Policy/Handler/Journal/Lock 断言顺序严格为：project → authorize → idempotency → lock →
re-project → re-authorize → STARTED → handler → terminal receipt → project → verify。拒绝时 Handler 调用次数为 0。

- [ ] **Step 2: 写重试、取消和不确定状态测试**

相同 operationId 完成后返回历史引用；IN_PROGRESS/UNCERTAIN 不执行 Handler；取消向 Handler context 传播；Handler
成功但终态写入失败返回 `OPERATION_UNCERTAIN`，不伪造失败后自动重跑。

- [ ] **Step 3: 运行并确认 RED**

```powershell
mvn -pl ada-core -am test "-Dtest=AnalysisCoordinatorTest,OperationIdempotencyServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现最小管线和不可变 Registry**

Registry 构造完成后拒绝重复 Action；`TARGET_EXECUTION` binding 强制要求 operationId 和文件锁；只读动作不写
Operation Journal。所有异常映射保留 cause，Policy 拒绝归档 decisionId。

- [ ] **Step 5: 运行并 Commit**

```powershell
mvn -pl ada-core -am test
git add ada-core
git commit -m "feat: coordinate all analysis actions"
```

### Task 8: 注册 15 个动作、证据义务与结论门禁

**Files:**
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/LifecycleActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ReadActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/TargetExecutionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionHandlers.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionInputs.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ConclusionGate.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceObligationEvaluator.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceObligationEvaluatorTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisActionRegistryTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/ConclusionGateTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`

**Interfaces:**
- Produces: 15 个 Action 每个恰好一个 Binding；`ControlPlaneServices.coordinator()`。
- Consumes: 当前 ApplicationService 和 Task 2/3/6/7 契约。

Policy 矩阵固定为：

| Action | 最小前置条件 | 副作用等级 |
|---|---|---|
| `ANALYSIS_BEGIN` | Project 已登记、target selector 合法 | CASE_WRITE |
| `CASE_INSPECT/CASE_AUDIT/GANTT_INSPECT/ARTIFACT_READ/EVIDENCE_QUERY` | Case 与 Artifact 身份有效 | READ_ONLY |
| `ALGORITHM_INPUT_CAPTURE` | Analysis 存在且输入尚未冲突 | CASE_WRITE |
| `RUN_TEST` | 输入已捕获 | TARGET_EXECUTION |
| `STATIC_ANALYZE` | 输入已捕获 | CASE_WRITE |
| `CODEPATH_PLAN_CREATE/JDWP_PLAN_CREATE` | Method Catalog 属于 Analysis | CASE_WRITE |
| `CODEPATH_COLLECT/JDWP_COLLECT` | Plan 属于 Case/Analysis 且预算有效 | TARGET_EXECUTION |
| `ANALYSIS_STATUS` | Case/Analysis 存在 | READ_ONLY |
| `ANALYSIS_FINALIZE` | Claim 引用有效、义务/矛盾/截断满足对应等级 | CASE_WRITE |

`CoreActionInputs` 使用下列精确 payload；Workspace、Project、Case、Analysis 身份只存在于 `ActionTarget`，不得在
payload 重复：

```java
public final class CoreActionInputs {
    public record AnalysisBegin(String question, TargetTest targetTest,
            Optional<CaseId> existingCaseId, Optional<String> adapterId) { }
    public record NoInput() { }
    public record GanttInspect(String artifactId, String operation,
            Optional<String> jsonPointer, int offset, int limit) { }
    public record CodePathPlanCreate(CodePathPlanRequest request) { }
    public record CodePathCollect(PlanId planId) { }
    public record JdwpPlanCreate(JdwpPlanRequest request) { }
    public record JdwpCollect(PlanId planId) { }
    public record ArtifactRead(String artifactId, long offsetBytes, int maxBytes) { }
    public record EvidenceQuery(String artifactId, EvidenceQueryRequest request) { }
    public record AnalysisFinalize(ConclusionCandidate candidate) { }

    private CoreActionInputs() { }
}
```

`CaseInspect`、`AlgorithmInputCapture`、`CaseAudit`、`RunTest`、`StaticAnalyze` 和 `AnalysisStatus` 使用 `NoInput`。

- [ ] **Step 1: 写 Registry 完整性和每类 Policy 失败测试**

断言 15 个枚举全部注册、无多余绑定；无 Analysis、跨 Case Plan、缺输入、损坏 Artifact、失败指纹
`CHANGED/INCOMPARABLE`、未满足义务和确认级别越权全部拒绝。

- [ ] **Step 2: 写结论等级测试**

`CONFIRMED_FACT` 要求确认资格和 Evidence ID；`VALIDATOR_CONCLUSION` 要求 Validator Artifact；
`SOURCE_INFERENCE` 只引用源码事实；`LLM_HYPOTHESIS` 允许证据不足但必须列 missing evidence；
`MISSING_EVIDENCE` 不得伪装为根因结论。

- [ ] **Step 3: 运行并确认 RED**

```powershell
mvn -pl evidence-engine,ada-core -am test "-Dtest=EvidenceObligationEvaluatorTest,AnalysisActionRegistryTest,ConclusionGateTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现 Binding 并接入 ControlPlaneServices**

Handler 只适配现有服务，不复制其业务实现；Policy 不启动进程。`analysis_status` 直接返回 ControlView；
`analysis_finalize` 先 Gate，后追加 candidate/accepted 或 candidate/rejected 决策。

- [ ] **Step 5: 运行并 Commit**

```powershell
mvn -pl evidence-engine,ada-core -am test
git add evidence-engine ada-core
git commit -m "feat: register coordinated analysis lifecycle"
```

### Task 9: 提取唯一共享 Runtime 组合根

**Files:**
- Create: `algorithm-debug-runtime/pom.xml`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntime.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrap.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeBootstrapRequest.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeBootstrapException.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeToolchain.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeToolchainResolver.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/CodePathRuntimeFactory.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/JdwpRuntimeFactory.java`
- Create: `algorithm-debug-runtime/src/test/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrapTest.java`
- Create: `algorithm-debug-runtime/src/test/java/org/example/algorithmdebug/runtime/RuntimeToolchainResolverTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: `AlgorithmDebugRuntime bootstrap(RuntimeBootstrapRequest request)`；CLI 和 MCP 唯一调用。
- Consumes: `ControlPlaneServices` 的完整依赖注入 overload、ServiceLoader Adapter、CodePath/JDWP 实现。

请求签名固定为：

```java
public record RuntimeBootstrapRequest(
        Map<String, String> environment,
        Properties systemProperties,
        Clock clock,
        AgentExecutionLog executionLog) {
}
```

- [ ] **Step 1: 写组合根失败测试**

覆盖 Java 低于 21、目标 Java 不存在、显式 Maven 不存在、Adapter 空集合、CodePath/JDWP 未配置的可诊断降级、
环境变量 Windows 大小写、`close()` 幂等。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl algorithm-debug-runtime -am test
```

Expected: FAIL，因为模块尚不存在。

- [ ] **Step 3: 迁移 AdaMain 中的具体装配代码**

Runtime 依赖 `method-path-codepathtracer`、`jdwp-collector-adapter` 和 Adapter SPI 实现；`ada-core` 继续只依赖 SPI。
所有配置错误使用 `RuntimeBootstrapException(code,cause)`，不引用 CLI/MCP 错误类型。

- [ ] **Step 4: 运行并 Commit**

```powershell
mvn -pl algorithm-debug-runtime -am test
git add pom.xml algorithm-debug-runtime
git commit -m "refactor: centralize algorithm runtime bootstrap"
```

### Task 10: 让 CLI 复用 Runtime 与 Coordinator

**Files:**
- Modify: `algorithm-debug-cli/pom.xml`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCoordinatedResultAdapter.java`
- Delete: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/RuntimeToolchain.java`
- Create: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCoordinatorCompatibilityTest.java`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/AdaMainTest.java`

**Interfaces:**
- Produces: 历史 `ToolResponse 2.0` 输出不变；模型动作经 Coordinator；workspace/project/doctor 管理命令直连。
- Consumes: `AlgorithmDebugRuntime` 和 `CoordinatedToolResult`。

映射规则固定为：

```text
SUCCEEDED -> ToolResponse.success(data, artifacts)
REJECTED  -> ToolResponse.failure(code, message, artifacts)
FAILED    -> ToolResponse.failure(code, sanitizedMessage, artifacts)
```

- [ ] **Step 1: 写兼容与不可绕过失败测试**

用 fake Coordinator 断言 run/static/plan/collect/read/query 调用 Coordinator；越序返回旧 CLI failure；目标 UT 失败仍是
成功 ToolResponse data；workspace-init/project-register/doctor 不进入 Coordinator。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl algorithm-debug-cli -am test "-Dtest=CliCoordinatorCompatibilityTest,AdaMainTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 删除 CLI 私有组合根并接入 Runtime**

`AdaMain.defaultApplication()` 只构造 `RuntimeBootstrapRequest`、调用 bootstrap、创建 CLI Adapter。不得保留第二份
ServiceLoader、CodePath、JDWP 或 Maven 装配。

- [ ] **Step 4: 执行 CLI 回归并 Commit**

```powershell
mvn -pl algorithm-debug-cli -am test
git add algorithm-debug-cli
git commit -m "refactor: route cli actions through coordinator"
```

### Task 11: 建立 Java stdio MCP Server 与协议生命周期

**Files:**
- Create: `algorithm-debug-mcp-server/pom.xml`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AlgorithmDebugMcpMain.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpServerBootstrap.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpServerConfiguration.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AlgorithmDebugMcpServer.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpServerLifecycle.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpProtocolErrorMapper.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpServerBootstrapTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpServerLifecycleTest.java`
- Modify: `pom.xml`

**Interfaces:**
- Produces: MCP initialize/ping/shutdown；启动时初始化 Agent Workspace 并登记一个目标 Project；stdio stdout 无日志污染；10 秒优雅关闭。
- Consumes: `AlgorithmDebugRuntimeBootstrap`、MCP SDK 2.0.1、Jackson 2 mapper。

Server 创建骨架固定为：

```java
McpJsonMapper mapper = new JacksonMcpJsonMapper(objectMapper);
StdioServerTransportProvider transport =
        new StdioServerTransportProvider(mapper, stdin, stdout);
McpSyncServer server = McpServer.sync(transport)
        .serverInfo("algorithm-debug-agent", version)
        .requestTimeout(Duration.ofMinutes(20))
        .strictToolNameValidation(true)
        .validateToolInputs(true)
        .tools(toolSpecifications)
        .build();
```

- [ ] **Step 1: 写 Server 组装、生命周期和 stdout 污染失败测试**

使用注入的 stdin/stdout/stderr 和 fake Runtime 测试 Server 创建、initialize capability、活动调用计数、取消和
close；stderr 允许日志，任何生产代码向 stdout 写 banner/日志都使测试失败。标准 Client 启动 shaded JAR 的测试
放在 Task 14 的 Failsafe 阶段，避免 unit test 依赖尚未产生的 package Artifact。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl algorithm-debug-mcp-server -am test
```

- [ ] **Step 3: 实现生命周期与基础设施错误映射**

仅协议解析、未知工具、Schema、取消和内部异常设置 MCP `isError=true`；领域拒绝以后由 structured result 返回。
`McpServerBootstrap` 从受信配置取得 Agent Workspace、从 `-ProjectRoot` 取得目标仓库，调用 Workspace init 和 Project
register 后冻结 `ProjectId`；一个 stdio Server 实例只服务一个 Project。关闭顺序为拒绝新请求、取消/等待活动请求、
关闭 Runtime、关闭 Server。

- [ ] **Step 4: 运行并 Commit**

```powershell
mvn -pl algorithm-debug-mcp-server -am test
git add pom.xml algorithm-debug-mcp-server
git commit -m "feat: add java stdio mcp server"
```

### Task 12: 暴露 15 个 MCP Tool、结果、Resource 与 Prompt

**Files:**
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolDescriptor.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolCatalog.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolDispatcher.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpRequestContextResolver.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpResultMapper.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AgentResourceProvider.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AgentPromptProvider.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/AnalysisBeginInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/CaseInspectInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/AlgorithmInputCaptureInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/CaseAuditInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/GanttInspectInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/RunTestInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/StaticAnalyzeInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/CodePathPlanCreateInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/CodePathCollectInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/JdwpPlanCreateInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/JdwpCollectInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/ArtifactReadInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/EvidenceQueryInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/AnalysisStatusInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/AnalysisFinalizeInput.java`
- Create: `schemas/mcp/tools/analysis-begin-v1.schema.json`
- Create: `schemas/mcp/tools/case-inspect-v1.schema.json`
- Create: `schemas/mcp/tools/algorithm-input-capture-v1.schema.json`
- Create: `schemas/mcp/tools/case-audit-v1.schema.json`
- Create: `schemas/mcp/tools/gantt-inspect-v1.schema.json`
- Create: `schemas/mcp/tools/run-test-v1.schema.json`
- Create: `schemas/mcp/tools/static-analyze-v1.schema.json`
- Create: `schemas/mcp/tools/codepath-plan-create-v1.schema.json`
- Create: `schemas/mcp/tools/codepath-collect-v1.schema.json`
- Create: `schemas/mcp/tools/jdwp-plan-create-v1.schema.json`
- Create: `schemas/mcp/tools/jdwp-collect-v1.schema.json`
- Create: `schemas/mcp/tools/artifact-read-v1.schema.json`
- Create: `schemas/mcp/tools/evidence-query-v1.schema.json`
- Create: `schemas/mcp/tools/analysis-status-v1.schema.json`
- Create: `schemas/mcp/tools/analysis-finalize-v1.schema.json`
- Modify: `algorithm-debug-mcp-server/pom.xml`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpToolCatalogTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpToolDispatcherTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpResultMapperTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpRequestContextResolverTest.java`

**Interfaces:**
- Produces: 15 个 Tool、4 个 Resource、3 个 Prompt；所有 Tool 只调用 Dispatcher → Coordinator。
- Consumes: Task 3/7/8 协调契约与 Agent Definition 资源。

工具与输入类型固定映射：

| Tool | Input record | Action |
|---|---|---|
| `analysis_begin` | `AnalysisBeginInput` | `ANALYSIS_BEGIN` |
| `case_inspect` | `CaseInspectInput` | `CASE_INSPECT` |
| `algorithm_input_capture` | `AlgorithmInputCaptureInput` | `ALGORITHM_INPUT_CAPTURE` |
| `case_audit` | `CaseAuditInput` | `CASE_AUDIT` |
| `gantt_inspect` | `GanttInspectInput` | `GANTT_INSPECT` |
| `run_test` | `RunTestInput` | `RUN_TEST` |
| `static_analyze` | `StaticAnalyzeInput` | `STATIC_ANALYZE` |
| `codepath_plan_create` | `CodePathPlanCreateInput` | `CODEPATH_PLAN_CREATE` |
| `codepath_collect` | `CodePathCollectInput` | `CODEPATH_COLLECT` |
| `jdwp_plan_create` | `JdwpPlanCreateInput` | `JDWP_PLAN_CREATE` |
| `jdwp_collect` | `JdwpCollectInput` | `JDWP_COLLECT` |
| `artifact_read` | `ArtifactReadInput` | `ARTIFACT_READ` |
| `evidence_query` | `EvidenceQueryInput` | `EVIDENCE_QUERY` |
| `analysis_status` | `AnalysisStatusInput` | `ANALYSIS_STATUS` |
| `analysis_finalize` | `AnalysisFinalizeInput` | `ANALYSIS_FINALIZE` |

- [ ] **Step 1: 写 Catalog 唯一性、Schema 和注解失败测试**

断言名称唯一、15 个 Action 完整、Schema 资源存在、未知字段拒绝、请求上限 1 MiB。只读 Tool 标记
`readOnlyHint=true`；有副作用 Tool 不声明 idempotent，只有携带 operationId 且服务端可恢复的 Tool 才声明
`idempotentHint=true`。

`McpRequestContextResolverTest` 还必须覆盖：启动 ProjectRoot 位于 trusted roots、MCP roots 包含同一规范路径、多个 roots
中只有一个匹配、roots 不支持时使用已冻结 Project、roots 与启动 Project 冲突、file URI 解码、Windows 大小写、
符号链接/重解析点和路径逃逸。冲突必须返回 `MCP_PROJECT_ROOT_MISMATCH`，不得接受模型输入路径。

- [ ] **Step 2: 写 Dispatcher/Mapper 错误分层测试**

目标 UT `FAILED/ERROR`、采集 `TARGET_FAILED` 是 `isError=false`；Coordinator `REJECTED` 也是协议成功但
structured `outcome=REJECTED`；未知工具、坏 Schema、Server 异常是 `isError=true`。

- [ ] **Step 3: 运行并确认 RED**

```powershell
mvn -pl algorithm-debug-mcp-server -am test "-Dtest=McpToolCatalogTest,McpToolDispatcherTest,McpResultMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现 Catalog、Dispatcher、Resources 和 Prompts**

Resource URI 固定为 `ada://agent/manifest`、`ada://agent/capabilities`、`ada://cases/{caseId}/digest`、
`ada://cases/{caseId}/analyses/{analysisId}/status`；Prompt 固定为 `algorithm-debug/start`、
`algorithm-debug/continue`、`algorithm-debug/explain-evidence`。Maven 将根目录 `schemas/mcp` 作为只读 resource 复制
进 JAR，避免维护第二份 Schema。单响应默认 256 KiB，超过后返回 Artifact 引用。

- [ ] **Step 5: 运行协议回归并 Commit**

```powershell
mvn -pl algorithm-debug-mcp-server -am test
git add algorithm-debug-mcp-server schemas/mcp
git commit -m "feat: expose coordinated mcp capabilities"
```

### Task 13: 建立 Canonical Agent Definition 与 Qwen CLI Extension

**Files:**
- Create: `agent-definition/algorithm-debug-agent-v1.json`
- Create: `agent-definition/system-prompt-v1.md`
- Create: `agent-definition/completion-contract-v1.schema.json`
- Create: `agent-definition/README.md`
- Create: `integrations/host-adapter-kit/schemas/host-adapter-manifest-v1.schema.json`
- Create: `integrations/host-adapter-kit/scripts/render-host-config.mjs`
- Create: `integrations/host-adapter-kit/test/render-host-config.test.mjs`
- Create: `integrations/qwen-cli/qwen-extension.json`
- Create: `integrations/qwen-cli/agents/algorithm-debug.md`
- Create: `integrations/qwen-cli/adapter-manifest.json`
- Create: `integrations/qwen-cli/install.ps1`
- Create: `integrations/qwen-cli/check.ps1`
- Create: `integrations/qwen-cli/uninstall.ps1`
- Create: `integrations/qwen-cli/test/qwen-extension.test.mjs`
- Modify: `skills/algorithm-debug/SKILL.md`

**Interfaces:**
- Produces: Qwen Extension 提供一个 MCP Server 和一个名为 `algorithm-debug` 的子 Agent。
- Consumes: Canonical Prompt、`bin/ada-mcp.cmd`、15 个 MCP Tool。

Qwen Extension 的服务器定义固定为：

```json
{
  "name": "algorithm-debug-agent",
  "version": "0.1.0",
  "mcpServers": {
    "algorithm-debug": {
      "command": "powershell.exe",
      "args": [
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        "${extensionPath}${/}scripts${/}run-ada-mcp.ps1",
        "-Config",
        "${extensionPath}${/}config${/}mcp-agent-settings.json",
        "-ProjectRoot",
        "${workspacePath}"
      ],
      "cwd": "${extensionPath}",
      "timeout": 1200000
    }
  },
  "agents": "agents"
}
```

子 Agent frontmatter 必须使用 `model: inherit`、`approvalMode: auto-edit`，工具 allowlist 只包含
`mcp__algorithm-debug__analysis_begin` 到 `mcp__algorithm-debug__analysis_finalize` 的 15 个精确名称；正文由
`system-prompt-v1.md` 生成并校验 SHA-256，不手工维护第二份规则。

该 manifest 格式以 Qwen 官方 Extension 的 `qwen-extension.json`、`${extensionPath}`/`${/}` 路径替换和
`agents/` 发现规则为基线；不得写 `trust: true`。实现时以
[Qwen Extension 官方说明](https://github.com/QwenLM/qwen-code/blob/main/docs/users/extension/getting-started-extensions.md)、
[Qwen MCP 官方说明](https://github.com/QwenLM/qwen-code/blob/main/docs/users/features/mcp.md)和
[Qwen Subagent 官方说明](https://github.com/QwenLM/qwen-code/blob/main/docs/users/features/sub-agents.md)为契约来源。

- [ ] **Step 1: 写 Definition、生成一致性和安装安全失败测试**

测试 Prompt hash、15 工具 allowlist、Extension 无绝对开发机路径、安装前备份、重复安装幂等、卸载只删除本
Extension、配置损坏时拒绝覆盖。

- [ ] **Step 2: 运行并确认 RED**

```powershell
node --test integrations/host-adapter-kit/test/render-host-config.test.mjs integrations/qwen-cli/test/qwen-extension.test.mjs
```

- [ ] **Step 3: 实现生成器和 PowerShell 生命周期**

生成器只读取 Agent Definition，不复制 Tool Schema。安装使用 staging 后原子切换；`check.ps1` 验证 Qwen 可执行、
Extension manifest、Agent frontmatter、MCP initialize 和 tools/list；不写模型凭据。

- [ ] **Step 4: 验证官方 Qwen 格式并 Commit**

```powershell
node --test integrations/host-adapter-kit/test/render-host-config.test.mjs integrations/qwen-cli/test/qwen-extension.test.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File integrations/qwen-cli/check.ps1 -RepositoryRoot . -Offline
git add agent-definition integrations/host-adapter-kit integrations/qwen-cli skills/algorithm-debug/SKILL.md
git commit -m "feat: package qwen algorithm debug subagent"
```

### Task 14: 打包 MCP、启动脚本和配置

**Files:**
- Create: `config/mcp-agent-settings.json`
- Create: `schemas/config/mcp-agent-settings-v1.schema.json`
- Create: `bin/ada-mcp.cmd`
- Create: `scripts/run-ada-mcp.ps1`
- Modify: `bin/README.md`
- Modify: `config/README.md`
- Modify: `scripts/build-agent.ps1`
- Modify: `algorithm-debug-mcp-server/pom.xml`
- Modify: `.gitignore`
- Modify: `THIRD_PARTY_NOTICES.md`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpPackagingTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/ShadedStdioMcpIT.java`

**Interfaces:**
- Produces: shaded `algorithm-debug-mcp-server-*-all.jar`、`bin/ada-mcp.cmd`、不含凭据的配置。
- Consumes: Runtime/MCP 模块、Agent Java 21、Collector/Launcher JAR。

- [ ] **Step 1: 写包内容和启动参数失败测试**

断言 shaded JAR 有 Main-Class、MCP SDK/Jackson ServiceLoader 资源、Agent Definition 和 Tool Schema；脚本拒绝未知参数、
任意主类和任意 classpath；`-ProjectRoot` 必须存在、是受信目录且不能是 extension 目录；配置不含 OpenCode 字段、
凭据或绝对开发机路径。

`ShadedStdioMcpIT` 使用 SDK
`McpClient.sync(new StdioClientTransport(parameters, McpJsonDefaults.getMapper()))` 启动实际 shaded JAR，完成
initialize、ping、tools/list、close；stderr 允许有界日志，stdout 非 JSON-RPC 字节会使 Client 初始化失败。

- [ ] **Step 2: 运行并确认 RED**

```powershell
mvn -pl algorithm-debug-mcp-server -am package "-DskipTests=false"
```

- [ ] **Step 3: 实现构建复制和启动脚本**

`build-agent.ps1` 校验 CLI、MCP、CodePath Launcher、JDWP Collector 四类产物；运行期配置优先级固定为启动参数 →
环境变量 → `mcp-agent-settings.json`，未知字段拒绝。配置固定包含 `schemaVersion`、`workspaceDirectory`、
`trustedProjectRoots`、`resultJsonDirectory`、`agentJavaHome`、`targetJavaHome`、`mavenExecutable`、并发/请求/响应/关闭
预算和 DFX 开关；不包含宿主配置。stdio 模式不打印启动 banner 到 stdout。
MCP 模块在 `package` 阶段先生成 shaded JAR，再由 Failsafe 执行 `ShadedStdioMcpIT`，并通过
`ada.mcp.shadedJar` system property 传入实际 Artifact 路径。

- [ ] **Step 4: 执行构建并 Commit**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-agent.ps1
git add config schemas/config/mcp-agent-settings-v1.schema.json bin scripts/build-agent.ps1 .gitignore THIRD_PARTY_NOTICES.md algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpPackagingTest.java
git commit -m "build: package portable mcp agent"
```

### Task 15: 完整集成、跨进程、Eval 和迁移审计

**Files:**
- Modify: `integration-tests/pom.xml`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/McpAnalysisLifecycleIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/McpTargetExecutionConcurrencyIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/SuccessfulUtEvidenceIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/FailedUtFingerprintIT.java`
- Create: `agent-evals/suites/mcp-portable-smoke.json`
- Modify: `agent-evals/run.mjs`
- Modify: `agent-evals/README.md`
- Modify: `README.md`
- Modify: `docs/architecture/README.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/current-capabilities.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Create: `docs/audits/2026-09-25-portable-mcp-subagent-implementation-audit.md`

**Interfaces:**
- Produces: 可复现的完整测试/Eval/性能结果和最终实现审计。
- Consumes: 前 14 个任务全部产物。

- [ ] **Step 1: 写四条真实集成链路并确认初始失败**

Lifecycle 覆盖 begin → input → run → static → plan → collect → query → status → finalize；成功 UT 无普通基准仍保留证据；
断言失败与异常失败分别验证指纹；Concurrency 启动两个独立 MCP Server，断言只有一个目标进程执行。
`integration-tests/pom.xml` 配置 Failsafe 在 `integration-test/verify` 阶段执行 `*IT.java`，Unit 测试仍由 Surefire 执行。

```powershell
mvn -pl integration-tests -am verify "-Dit.test=McpAnalysisLifecycleIT,McpTargetExecutionConcurrencyIT,SuccessfulUtEvidenceIT,FailedUtFingerprintIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

- [ ] **Step 2: 修正集成暴露的问题并运行根测试**

只修正对应生产根因，不放宽断言或 Golden：

```powershell
mvn -Pcodepath-launcher test
mvn -Pcodepath-launcher verify
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-agent.ps1
```

- [ ] **Step 3: 执行 Node、Qwen 安装和标准 MCP 验证**

```powershell
$tests = Get-ChildItem integrations/host-adapter-kit/test,integrations/qwen-cli/test,agent-evals/test -Filter *.test.mjs -Recurse
node --test $tests.FullName
powershell -NoProfile -ExecutionPolicy Bypass -File integrations/qwen-cli/install.ps1 -RepositoryRoot . -Scope TestProfile
powershell -NoProfile -ExecutionPolicy Bypass -File integrations/qwen-cli/check.ps1 -RepositoryRoot . -Scope TestProfile
powershell -NoProfile -ExecutionPolicy Bypass -File integrations/qwen-cli/uninstall.ps1 -RepositoryRoot . -Scope TestProfile
```

- [ ] **Step 4: 执行性能与 Agent Eval**

记录 MCP 冷启动、热 `analysis_status`、10,000 Artifact 投影、双进程锁、关闭时间；运行 10-Case Smoke，每个 Case 3 次，
比较动作序列、Evidence 引用、结论等级、额外采集次数和停止点，不比较自然语言逐字一致。

```powershell
node agent-evals/run.mjs --suite agent-evals/suites/mcp-portable-smoke.json --repetitions 3
```

- [ ] **Step 5: 更新文档、写最终审计和全量检查**

审计记录精确 commit、依赖树、测试数、跳过项、性能数据、Qwen 版本、Prompt/Catalog/Policy 版本、已知限制和回滚命令。

```powershell
git diff --check
git status --short
```

- [ ] **Step 6: 请求代码审查并 Commit**

使用 `superpowers:requesting-code-review` 审查完整实现，修复 P0/P1 和范围内 P2 后：

```powershell
git add README.md docs integration-tests agent-evals
git commit -m "docs: audit portable mcp subagent implementation"
```

## Definition of Done

- 15 个 MCP Tool 从唯一 Catalog 暴露，所有调用不可绕过 Coordinator，普通 Tool Call 不启动 `ada.cmd`。
- CLI 与 MCP 依赖同一个 `algorithm-debug-runtime` 组合根；没有重复 ServiceLoader/Collector/Maven 装配。
- 成功 UT 无失败基准时动态数据不丢弃；失败 UT 只有 `MATCHED` 能确认同类失败。
- 历史 Case/ToolResponse/Collection 可读，新 Operation/Decision/Conclusion 只追加且可在 Server 重启后恢复。
- 两个 Server 对同一 Project 的目标执行互斥；取消、崩溃和不确定 operation 不自动重复 UT。
- MCP initialize/list/call/resource/prompt/shutdown、Schema、错误分层、stdout 纯净性和响应预算通过契约测试。
- Qwen Extension 可安装、检查、重复安装和卸载；子 Agent 只获得 15 个 Algorithm Debug MCP Tool。
- 根 Maven 测试、构建脚本、Node 测试、真实集成、10-Case × 3 Eval 和最终审计通过。
- 公司另外 5 个工具和第二个真实宿主若未提供，最终审计明确标记为发布前外部输入，不用推测实现冒充完成。

## Spec Coverage

| 设计要求 | 实施任务 |
|---|---|
| 成功/失败 UT 证据语义拆分 | Task 2 |
| 版本化协调/结论/operation 契约 | Task 3 |
| 追加式落盘、幂等与冲突检测 | Task 4 |
| 跨进程目标执行互斥 | Task 5、Task 15 |
| 重启后状态恢复和有界投影 | Task 6 |
| Coordinator 唯一执行管线 | Task 7 |
| 15 个 Action、证据义务和结论门禁 | Task 8 |
| CLI/MCP 共用组合根 | Task 9、Task 10 |
| Java 原生 stdio MCP | Task 11 |
| Tool/Resource/Prompt 和错误分层 | Task 12 |
| Canonical Agent Definition 和薄 Host Adapter | Task 13 |
| 配置、打包、许可证和启动安全 | Task 14 |
| E2E、性能、Eval、文档、迁移和审计 | Task 15 |

公司环境另外 5 个工具的导入和第二个真实宿主适配不在本计划中猜测实现。两项都使用本计划产出的 Catalog、
Adapter Kit 和相同 Eval Suite；获得真实契约后分别编写增量设计与计划，不修改 Coordinator/Core 边界。
