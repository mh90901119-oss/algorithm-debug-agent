# Evidence-Constrained Portable MCP Algorithm Debug Subagent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 OpenCode 专用调试工具重构为 Java 原生、宿主无关、具备有界源码理解、可证伪调查账本、确定性观测评估和因果结论门禁的 MCP Algorithm Debug 子 Agent。

**Architecture:** `algorithm-debug-runtime` 是 CLI 与 MCP 共用的唯一生产组合根；所有模型动作经 `AnalysisCoordinator`，从追加式 Case/Investigation 产物重建状态。模型负责提出 Problem Frame、竞争假设和采集选择；Java 代码负责 Source Query、Predicate 三值评估、反证保留、证据资格、因果链等级、并发、幂等、归档和恢复。

**Tech Stack:** Java 21、Maven 3.9+、JUnit 5、Jackson 2.17.2、JSON Schema Draft 2020-12、官方 MCP Java SDK 2.0.1、PowerShell、Node.js Eval Harness、Qwen CLI Extension。

**Spec:** `docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md` 1.3；`docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`。

**Execution location:** 当前根仓库 `D:\javacode\algorithm-debug-agent`；不创建或使用 worktree。用户未跟踪目录 `docs/sharing/` 不修改、不暂存、不提交。

## Global Constraints

- Java 固定为 21；MCP SDK 固定为 `io.modelcontextprotocol.sdk:mcp-core:2.0.1` 与 `mcp-json-jackson2:2.0.1`；不引入 Spring、Jackson 3 或新的 Agent 编排框架。
- `ada-contracts` 不依赖实现模块；`algorithm-debug-runtime` 不依赖 CLI、MCP Server 或宿主 Adapter；Adapter 不反向依赖 `ada-core`。
- MCP Server 不调用模型、不保存模型凭据、不实现第二套 Agent Loop。知识 MD 是可选模型上下文，不是 Java Core 依赖、Evidence 或结论门禁输入。
- 当前基线 Catalog 由 17 个 `AnalysisActionType` 枚举值计算：现有 13 个，加 `source_query`、`investigation_update`、`analysis_status`、`analysis_finalize`。生产代码不得硬编码工具数量。
- Source Query mode 固定为 `METHOD/CALLERS/CALLEES/REACHABLE_PATH/SOURCE_WINDOW/SEARCH_SYMBOL`；Predicate operator 固定为 `METHOD_OBSERVED/RECORD_EXISTS/VALUE_EQUALS/VALUE_CHANGED/COUNT_COMPARE/PATH_CONTAINS/FAILURE_FINGERPRINT_MATCHES`。
- Source Query 默认预算：20 方法、50 边、深度 3、5 条路径、200 行、64 KiB；硬上限：100 方法、500 边、深度 8、20 条路径、500 行、256 KiB。值只定义在 `SourceQueryLimits`。
- 调查默认/硬上限：5/20 个假设、10/50 个开放 Gap、4/8 个 Plan Predicate、1,000/10,000 个事件。值只定义在 `InvestigationLimits`。
- CodePath Plan 新写 v7，JDWP Plan 新写 v6；旧 v6/v5 可读但投影为 `LEGACY_UNSTRUCTURED`，不能自动满足结构化调查义务。
- 成功 UT：`baselineRequired=false`；失败 UT：只有失败指纹 `MATCHED` 可获得确认资格。`CHANGED/INCOMPARABLE` 数据保留为 `CLUE_ONLY`，不能改变假设状态。
- Case、Operation、Coordination、Investigation、Source Query、Conclusion 和 Evidence 全部追加；禁止覆盖历史、`current-state.json`、静默 fallback 或自动重跑不确定目标执行。
- 所有行为任务严格 Red-Green-Refactor：先写完整失败测试并看到预期 RED，再写最小实现，最后重构和模块回归。
- 禁止魔鬼数字、魔鬼字符串、boolean 多义、God Class、循环依赖、静态可变全局状态、空实现、占位接口、无追踪 TODO、吞异常、测试专用生产分支和永久兼容补丁。
- 预算、版本、状态、事件类型、Artifact type、reason code、错误码和协议字段使用职责所属常量、枚举或 value object；Schema 与 Java 常量必须有一致性测试。
- 每个任务只暂存自己列出的文件；提交前执行 `git diff --check` 和受影响模块测试，不得暂存 `docs/sharing/`。

## Review Focus

1. **成功采集没有普通 Run**：CodePath/JDWP 数据必须可读、`baselineRequired=false` 且不被丢弃；Task 2 的两个服务回归测试和 Task 20 E2E 固定该行为。
2. **完整无命中与部分无命中**：只有 `COMPLETE` 无命中可得到 FALSE；`PARTIAL/UNKNOWN` 必须得到 UNKNOWN；Task 9 的参数化测试固定该行为。
3. **失败指纹变化但值看似命中**：可以产生 bounded truth，但 disposition 为 `CLUE_ONLY`、`effectApplied=false`；Task 9 和 Task 20 固定该行为。
4. **静态分支存在但本次未执行**：Source Query 只能产生 Source Inference；确认性因果边必须有运行时 Evidence；Task 8、Task 12 和 Task 20 固定该行为。
5. **模型遗漏替代假设或忽略反证**：整体根因最多为 `BOUNDED_HYPOTHESIS`；两个有依据的候选、目标 SUPPORTED 且至少一个 REFUTED 才能 `CONFIRMED`；Task 12 和收敛 Eval 固定该行为。

---

## File Structure

| 路径 | 单一职责 |
|---|---|
| `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceEligibility.java` | 可读性、完整性、基准要求、失败复现、义务和确认资格的正交契约 |
| `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/` | Action、ControlView、Operation、Decision、Conclusion 和统一结果契约 |
| `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/` | Problem Frame、Hypothesis、Gap、Predicate、Evaluation、Source Query、CausalChain 和唯一 Limits |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/OperationJournal.java` | STARTED 与唯一终态追加归档 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/InvestigationEventArchive.java` | 调查事件原子追加，禁止覆盖 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/InvestigationJournalReader.java` | sequence/hash/schema/identity 有界校验 |
| `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLock.java` | Project 级跨进程目标执行锁 |
| `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/SourceQueryService.java` | 六种有界源码查询统一入口 |
| `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationEvaluator.java` | Predicate 与 Evidence 的三值、disposition 和 provenance 评估 |
| `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/HypothesisEvidenceReducer.java` | 支持、反证、未知和终态聚合 |
| `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/CausalChainEligibilityEvaluator.java` | 因果链引用、替代假设和完成等级评估 |
| `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinator.java` | 唯一授权、锁、幂等、执行和后置校验模板 |
| `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/InvestigationStateProjector.java` | 调查事件确定性重放 |
| `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrap.java` | CLI/MCP 唯一生产组合根 |
| `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpToolCatalog.java` | 17 个 Action 的唯一 Tool/Schema/annotation 映射 |
| `agent-definition/*` | 宿主无关 Agent 身份、Prompt、能力和完成契约 |
| `agent-evals/convergence-grade.mjs` | 根因类别、因果链、关键 Evidence 和停止状态收敛评分 |

### Task 1: 冻结基线、依赖和旧计划替代事实

**Files:**
- Create: `docs/development/evidence-constrained-mcp-implementation-baseline.md`
- Modify: `docs/architecture/README.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify: `docs/decisions/ADR-018-java-native-mcp-portable-subagent.md`

**Interfaces:**
- Consumes: spec 0.4、当前 Reactor、MCP SDK 坐标、当前 13 工具清单。
- Produces: 可审计依赖/工具/Schema/测试基线；明确本计划是唯一可执行计划。

- [ ] **Step 1: 写基线验证清单**

在 baseline 文档中记录以下确定内容：Java/Maven/Jackson/MCP 版本、17 个 Action 名称、六种 Source Query mode、七种 Predicate operator、当前 Schema 版本、当前 Maven/Node 测试命令、公司未知 5 工具不在范围内、`docs/sharing/` 为用户资产。Qwen CLI 和 DeepSeek Harness 分别记录可执行文件版本、真实配置路径/格式、stdio MCP 声明方式和子 Agent 定义方式；若本地或用户材料不能证明 DeepSeek Harness 的真实契约，则明确记为发布阻断项，禁止按猜测生成“兼容”配置。

- [ ] **Step 2: 执行依赖和现状验证**

Run:

```powershell
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-core:2.0.1"
mvn -q dependency:get "-Dartifact=io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1"
mvn -Pcodepath-launcher test
node --test integrations/opencode/test/*.test.mjs agent-evals/test/*.test.mjs
```

Expected: 依赖可解析，现状测试全绿。失败时把精确命令和错误写入 baseline，停止生产代码，不临时换 SDK。

- [ ] **Step 3: 更新当前索引和 ADR**

`docs/architecture/README.md` 指向 spec 0.4 和 ADR-018；开发计划增加证据约束 MCP 阶段。ADR-018 补充 Source Query、Investigation Runtime、知识可选和“旧 0.3 计划失效”的不可逆边界。

- [ ] **Step 4: 文档验证**

Run:

```powershell
rg -n "0\.4|17 个|source_query|investigation_update|知识.*可选" docs/development/evidence-constrained-mcp-implementation-baseline.md docs/architecture/README.md docs/plans/algorithm-debug-agent-development-plan.md docs/decisions/ADR-018-java-native-mcp-portable-subagent.md
git diff --check
```

Expected: 四类冻结事实均可定位；diff 无 whitespace 错误。

- [ ] **Step 5: Commit**

```powershell
git add docs/development/evidence-constrained-mcp-implementation-baseline.md docs/architecture/README.md docs/plans/algorithm-debug-agent-development-plan.md docs/decisions/ADR-018-java-native-mcp-portable-subagent.md
git commit -m "docs: freeze evidence-constrained mcp baseline"
```

### Task 2: 拆分 Evidence 资格并修复成功 UT 无基准

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceEligibility.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceEligibilityReason.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionBaselineCheck.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionExecutionSummary.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationBindingStatus.java`
- Create: `schemas/collection/evidence-eligibility-v1.schema.json`
- Create: `schemas/collection/collection-execution-summary-v3.schema.json`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceEligibilityEvaluator.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceEligibilityTest.java`
- Modify: `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Modify: `trace-validator/src/test/java/org/example/algorithmdebug/validator/CollectionEvidenceValidatorTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/JdwpCollectionApplicationServiceTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/EvidenceEligibilityJsonTest.java`

**Interfaces:**
- Produces: `EvidenceEligibility evaluate(EvidenceEligibilityEvaluator.Context)`；`CollectionExecutionSummary.eligibility()`。
- Consumes: completion、target outcome、retained count、validation、`ComparisonOutcome`、limitations。

- [ ] **Step 1: 写完整失败测试**

```java
@Test void passingCollectionWithoutReferenceRunIsConfirmationEligible() { }
@Test void failedCollectionRequiresMatchedFingerprint() { }
@Test void changedFailureIsReadableButClueOnly() { }
@Test void incomparableFailureIsReadableButClueOnly() { }
@Test void zeroHitFailsObligationWithoutBecomingUnreadable() { }
@Test void truncatedCollectionIsReadableButIncomplete() { }
@Test void invalidArtifactIsNeverEligible() { }
@Test void legacySummaryLoadsAsUnknownWithoutAutoUpgrade() { }
@Test void successfulCodePathWithoutRunTestRegistersPrimaryArtifact() { }
@Test void successfulJdwpWithoutRunTestRegistersPrimaryArtifact() { }
```

- [ ] **Step 2: 运行并确认 RED**

Run:

```powershell
mvn -pl ada-contracts,evidence-engine,trace-validator,ada-core -am test "-Dtest=EvidenceEligibilityTest,EvidenceEligibilityJsonTest,CollectionEvidenceValidatorTest,CollectionApplicationServiceTest,JdwpCollectionApplicationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL，因为契约/evaluator 不存在，且成功无普通 Run 仍会被旧布尔拒绝。

- [ ] **Step 3: 实现正交契约和 evaluator**

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
        List<String> reasonCodes) { }
```

`baselineRequired = targetFailed`；`confirmationEligible = artifactReadable && collectionComplete && obligationSatisfied && (!baselineRequired || failureFingerprintMatched)`。Reason code 使用 `EvidenceEligibilityReason` 枚举，旧 `evidenceUsable()` 仅保留 deprecated reader adapter，新生产代码不得调用。

- [ ] **Step 4: 实现 v2 兼容和服务修复**

旧 summary 缺少 eligibility 时生成 `LEGACY_UNKNOWN`、`confirmationEligible=false`；新 Writer 只写 v3。成功 Collection 没有 reference Run 时写 `NOT_COMPARED` 且 `baselineRequired=false`，PostProcessing 不再对成功场景 `referenceRunId.orElseThrow()`。

- [ ] **Step 5: 运行 GREEN 和模块回归**

```powershell
mvn -pl ada-contracts,case-management,trace-validator,evidence-engine,ada-core -am test
git diff --check
```

Expected: 全绿，两个成功无 Run 回归测试证明 Artifact 已注册且可查询。

- [ ] **Step 6: Commit**

```powershell
git add ada-contracts case-management trace-validator evidence-engine ada-core schemas/collection
git commit -m "fix: separate dynamic evidence eligibility"
```

### Task 3: 建立 Coordination 公共契约和 Schema

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisActionType.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionOutcome.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionDecision.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionDecisionCode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionSideEffect.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/CoordinationErrorCode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisIdentity.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ActionTarget.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisActionRequest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/AnalysisControlView.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/EvidenceObligation.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/EvidenceObligationKind.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ObligationStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/CoordinatedToolResult.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/OperationId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/OperationReceipt.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionCandidate.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionClaim.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionDecision.java`
- Create: `schemas/coordination/analysis-action-request-v1.schema.json`
- Create: `schemas/coordination/analysis-control-view-v1.schema.json`
- Create: `schemas/coordination/action-decision-v1.schema.json`
- Create: `schemas/coordination/evidence-obligation-v1.schema.json`
- Create: `schemas/tool/coordinated-tool-result-v1.schema.json`
- Create: `schemas/coordination/operation-receipt-v1.schema.json`
- Create: `schemas/coordination/conclusion-candidate-v1.schema.json`
- Create: `schemas/coordination/conclusion-decision-v1.schema.json`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationContractsTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationSchemaTest.java`

**Interfaces:**
- Produces: sealed `AnalysisActionRequest<T>`、typed target/payload、统一控制结果和结论候选。
- Consumes: 现有 Case/Analysis/Run/Plan/Evidence ID value objects。

- [ ] **Step 1: 写契约失败测试**

```java
@Test void actionTypesExactlyMatchSeventeenBaselineActions() { }
@Test void targetRejectsCrossIdentityAndBlankWorkspace() { }
@Test void coordinatedResultRequiresControlForEveryOutcome() { }
@Test void rejectedResultCannotCarrySideEffectArtifacts() { }
@Test void conclusionClaimRequiresTypedClassificationAndReferences() { }
@Test void everyContractRoundTripsThroughItsSchema() { }
@Test void schemasRejectUnknownFieldsAndOversizedText() { }
```

用 `EnumSet.of(...)` 声明预期 Action 集合，不断言整数 `17`。

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl ada-contracts -am test "-Dtest=CoordinationContractsTest,CoordinationSchemaTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

Expected: FAIL with missing contract classes/resources。

- [ ] **Step 3: 实现最小不可变契约**

```java
public enum AnalysisActionType {
    ANALYSIS_BEGIN, CASE_INSPECT, ALGORITHM_INPUT_CAPTURE, CASE_AUDIT,
    GANTT_INSPECT, RUN_TEST, STATIC_ANALYZE, SOURCE_QUERY,
    INVESTIGATION_UPDATE, CODEPATH_PLAN_CREATE, CODEPATH_COLLECT,
    JDWP_PLAN_CREATE, JDWP_COLLECT, ARTIFACT_READ, EVIDENCE_QUERY,
    ANALYSIS_STATUS, ANALYSIS_FINALIZE
}
```

所有 record 构造器完成 null、文本、集合唯一性和 ID 归属校验。错误码使用枚举，JSON 使用稳定英文值，公共模型写中文 Javadoc。

- [ ] **Step 4: Schema 一致性 GREEN**

```powershell
mvn -pl ada-contracts -am test
```

Expected: 所有 Java fixture 与 Draft 2020-12 Schema 双向通过，未知字段拒绝。

- [ ] **Step 5: Commit**

```powershell
git add ada-contracts schemas/coordination schemas/tool
git commit -m "feat: define coordinated action contracts"
```

### Task 4: 建立 Investigation、Source Query 和 CausalChain 契约

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationLimits.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryLimits.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ProblemFrameId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/HypothesisId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/EvidenceGapId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationPredicateId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationEvaluationId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/CausalChainId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ProblemFrame.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/HypothesisRecord.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/HypothesisStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/HypothesisEvaluation.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/EvidenceGap.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/EvidenceGapStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationPredicate.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationOperator.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationTruth.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/EvidenceDisposition.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/PredicateRole.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/HypothesisEffect.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationSelector.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationEvaluation.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationBinding.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationEvent.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationUpdateCommand.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationState.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryMode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryCompleteness.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryRequest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryResult.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/CausalChain.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/CausalNode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/CausalNodeType.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/CausalEdge.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationErrorCode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/ObservationErrorCode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryErrorCode.java`
- Create: `schemas/investigation/problem-frame-v1.schema.json`
- Create: `schemas/investigation/hypothesis-record-v1.schema.json`
- Create: `schemas/investigation/evidence-gap-v1.schema.json`
- Create: `schemas/investigation/observation-predicate-v1.schema.json`
- Create: `schemas/investigation/observation-evaluation-v1.schema.json`
- Create: `schemas/investigation/investigation-binding-v1.schema.json`
- Create: `schemas/investigation/investigation-event-v1.schema.json`
- Create: `schemas/investigation/investigation-state-v1.schema.json`
- Create: `schemas/investigation/causal-chain-v1.schema.json`
- Create: `schemas/source-query/source-query-request-v1.schema.json`
- Create: `schemas/source-query/source-query-result-v1.schema.json`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/investigation/InvestigationContractsTest.java`
- Create: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/investigation/InvestigationSchemaTest.java`

**Interfaces:**
- Produces: 所有调查 value object/enums、七种 operator、六种 source mode、typed selector/effect、sealed event。
- Consumes: `CaseId`、`AnalysisId`、`EvidenceId`、`SourceAnchor`、`MethodCatalog` identity。

- [ ] **Step 1: 写完整契约测试**

```java
@Test void problemFrameRequiresSymptomExpectedActualTargetAndUnknown() { }
@Test void hypothesisCannotStartAsSupportedOrRefuted() { }
@Test void predicateUnknownEffectIsAlwaysNoChange() { }
@Test void predicateUsesOnlySevenFixedOperators() { }
@Test void sourceQueryUsesOnlySixFixedModes() { }
@Test void planBindingRequiresOpenGapHypothesisAndOneToEightPredicates() { }
@Test void causalEdgeRequiresEvidenceOrSourceReference() { }
@Test void limitsMatchJsonSchemaMaximums() { }
@Test void allInvestigationEventsRoundTripAndRejectUnknownFields() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl ada-contracts -am test "-Dtest=InvestigationContractsTest,InvestigationSchemaTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现类型和唯一 Limits**

```java
public enum ObservationTruth { TRUE, FALSE, UNKNOWN }
public enum EvidenceDisposition { CONFIRMATION_ELIGIBLE, CLUE_ONLY, INVALID }
public enum PredicateRole { CRITICAL, CORROBORATING }
public enum HypothesisEffect { SUPPORT, REFUTE, NO_CHANGE }
public enum HypothesisStatus { OPEN, SUPPORTED, REFUTED, INCONCLUSIVE }
public enum EvidenceGapStatus { OPEN, PLANNED, OBSERVED, UNRESOLVED, CLOSED }
```

`ObservationPredicate` 构造器强制 `onUnknown=NO_CHANGE`；typed selector 使用 sealed records，不使用 `Map<String,Object>`。所有 maxItems/maxLength 来自两个 Limits 类并由测试与 Schema 对比。

七个新 ID record 实现现有 `OpaqueIdentifier`，沿用同一格式和长度校验；不得在领域对象中退回裸 String ID。

- [ ] **Step 4: 实现 sealed 事件和因果链**

事件至少包含：`ProblemFrameDefined`、`HypothesisAdded`、`EvidenceGapAdded`、`PredicateRegistered`、`PlanBound`、`ObservationEvaluated`、`GapStatusChanged`、`HypothesisStatusChanged`。模型命令不得构造后三种系统事件。

- [ ] **Step 5: 运行 GREEN 和 Commit**

```powershell
mvn -pl ada-contracts -am test
git add ada-contracts schemas/investigation schemas/source-query
git commit -m "feat: define evidence-constrained investigation contracts"
```

### Task 5: 实现追加式 Operation、Decision 和 Investigation 归档

**Files:**
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/OperationJournal.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CoordinationDecisionArchive.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/InvestigationEventArchive.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/InvestigationJournalReader.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/OperationJournalTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/CoordinationDecisionArchiveTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/InvestigationEventArchiveTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/InvestigationJournalReaderTest.java`

**Interfaces:**
- Produces: append/start/complete/fail/uncertain、appendDecision、appendEvent、readValidatedEvents。
- Consumes: Task 3/4 contracts、现有原子 JSON writer 和 Artifact path policy。

- [ ] **Step 1: 写归档失败测试**

```java
@Test void operationAllowsStartedAndExactlyOneTerminalDocument() { }
@Test void secondTerminalDocumentIsRejected() { }
@Test void eventSequenceIsStrictlyIncreasingWithinAnalysis() { }
@Test void duplicateEventIdOrConflictingSequenceIsRejected() { }
@Test void crossAnalysisEventIsRejected() { }
@Test void interruptedAtomicWriteLeavesNoVisibleHalfDocument() { }
@Test void readerReportsGapAsLimitationAndConflictAsFailure() { }
@Test void corruptedHashOrUnknownMajorSchemaFailsClosed() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl case-management -am test "-Dtest=OperationJournalTest,CoordinationDecisionArchiveTest,InvestigationEventArchiveTest,InvestigationJournalReaderTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现布局和原子追加**

布局固定为 `operations/{id}`、`coordination/{id}.json`、`investigation/events/{sequence}-{eventId}.json`、`source-queries/{id}`、`conclusions/{id}`。复用现有临时文件 + 原子 move；文件名只从经过校验的 opaque ID 和 sequence 生成。

- [ ] **Step 4: 实现有界 reader**

Reader 按 sequence 数值排序，验证 schema/hash/analysis identity。缺号返回 limitation；重复/冲突返回 `INVESTIGATION_JOURNAL_CONFLICT`，不得挑“最新”文件。

- [ ] **Step 5: 运行 GREEN 和 Commit**

```powershell
mvn -pl case-management -am test
git add case-management
git commit -m "feat: archive coordinated investigation events"
```

### Task 6: 实现跨进程锁和两个状态 Projector

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLock.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisArtifactIndex.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceExecutionLockTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/AnalysisArtifactIndexTest.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjector.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/InvestigationStateProjector.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjectorTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/InvestigationStateProjectorTest.java`

**Interfaces:**
- Produces: `WorkspaceExecutionLock.Handle tryAcquire(ProjectId, Duration)`、`AnalysisControlView project(AnalysisIdentity)`、`InvestigationState project(List<InvestigationEvent>)`。
- Consumes: Task 2 eligibility、Task 5 repositories、现有 Case archive read ports。

- [ ] **Step 1: 写锁和状态失败测试**

```java
@Test void twoIndependentLockInstancesAllowOnlyOneOwner() { }
@Test void closingHandleReleasesOperatingSystemLock() { }
@Test void differentProjectsDoNotBlockEachOther() { }
@Test void sameArtifactsInDifferentEnumerationOrderProduceSameControlView() { }
@Test void artifactIndexIsBoundedStableAndRejectsDuplicateIdentity() { }
@Test void journalConflictMakesControlViewFailClosed() { }
@Test void problemFrameMustBeFirstAndUnique() { }
@Test void refutedHypothesisCannotReturnToSupported() { }
@Test void addingCriticalUnknownMovesSupportedToInconclusive() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl case-management,ada-core -am test "-Dtest=WorkspaceExecutionLockTest,AnalysisArtifactIndexTest,AnalysisStateProjectorTest,InvestigationStateProjectorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 OS lock 和确定性投影**

锁文件固定为 `projects/{projectId}/.control/target-execution.lock`，只保存非业务诊断元数据。`AnalysisArtifactIndex` 从 manifest/artifact metadata 构造有界、不可变、按 identity 排序的索引。Projector 只读已校验 Manifest/Journal 和该索引，不扫描 Raw，不保存 `current-state.json`；集合按 ID/sequence 稳定排序。

- [ ] **Step 4: 运行 GREEN、重复性和 Commit**

```powershell
mvn -pl case-management,ada-core -am test
git diff --check
git add case-management ada-core
git commit -m "feat: project durable analysis control state"
```

### Task 7: 实现不可绕过的 Coordinator 执行模板

**Files:**
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionPolicy.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionHandler.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionBinding.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionRegistry.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisPolicyExecutor.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoordinatedResultFactory.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ActionCancellation.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/WorkspaceExecutionLockManager.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinator.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisActionRegistryTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyServiceTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinatorTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/ActionCancellationTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/WorkspaceExecutionLockManagerTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/OperationJournal.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/OperationJournalTest.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/CoordinationErrorCode.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationSchemaTest.java`
- Modify: `schemas/coordination/*.schema.json`
- Modify: `schemas/tool/coordinated-tool-result-v1.schema.json`

**Interfaces:**
- Produces: `CoordinatedToolResult<?> execute(AnalysisActionRequest<?> request)`；不可变 Action registry。
- Consumes: Task 5 journals、Task 6 lock/projectors、typed Policy/Handler。

- [ ] **Step 1: 写执行模板失败测试**

```java
@Test void rejectedPolicyNeverInvokesHandlerOrWritesStarted() { }
@Test void targetActionAcquiresLockThenReprojectsBeforeExecution() { }
@Test void completedOperationIdReturnsPriorReceiptWithoutReexecution() { }
@Test void uncertainOperationIsNeverAutomaticallyRetried() { }
@Test void handlerFailurePreservesCauseAndWritesFailedTerminal() { }
@Test void cancellationRequestsBoundedTerminationAndWritesTerminal() { }
@Test void postconditionFailureReturnsFailedNotSucceeded() { }
@Test void everyRegisteredActionHasExactlyOnePolicyAndHandler() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl ada-core -am test "-Dtest=AnalysisActionRegistryTest,OperationIdempotencyServiceTest,AnalysisCoordinatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现模板方法**

执行顺序固定为：解析 binding → 投影 before → policy authorize → 归档授权 decision → 幂等检查 → 必要时获取锁 → 再投影 → 写 STARTED → handler → 投影 after → policy postcondition → 写唯一终态 → 归档后置 decision → 返回结果。成功终态必须晚于后置校验；否则追加式日志无法把校验失败的成功回执改成失败。Policy 不启动进程；Handler 不检查通用身份规则。

```java
public interface AnalysisActionPolicy<I, O> {
    ActionDecision authorize(AnalysisControlView before, AnalysisActionRequest<I> request);
    ActionDecision verify(AnalysisControlView before, AnalysisActionRequest<I> request,
            O result, AnalysisControlView after);
}

public interface AnalysisActionHandler<I, O> {
    O execute(ActionTarget target, I input, ActionCancellation cancellation);
}
```

- [ ] **Step 4: 运行 GREEN 和异常路径回归**

```powershell
mvn -pl ada-core -am test
```

- [ ] **Step 5: Commit**

```powershell
git add ada-core
git commit -m "feat: coordinate all analysis actions"
```

### Task 8: 实现有界 Source Query

**Files:**
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/MethodCatalogIndex.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/ReachablePathFinder.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/BoundedSourceWindowReader.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/SourceQueryService.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/SourceQueryException.java`
- Create: `static-analysis/src/test/java/org/example/algorithmdebug/staticanalysis/ReachablePathFinderTest.java`
- Create: `static-analysis/src/test/java/org/example/algorithmdebug/staticanalysis/BoundedSourceWindowReaderTest.java`
- Create: `static-analysis/src/test/java/org/example/algorithmdebug/staticanalysis/SourceQueryServiceTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/StaticAnalysisApplicationServiceTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/OpaqueIdGenerator.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/OpaqueIdGeneratorTest.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/InvestigationContractChecks.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/investigation/SourceQueryResult.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/investigation/InvestigationContractsTest.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/investigation/InvestigationSchemaTest.java`
- Modify: `schemas/source-query/source-query-result-v1.schema.json`
- Modify: `docs/designs/2026-09-25-portable-mcp-subagent-and-coordinator-design.md`

**Interfaces:**
- Produces: `SourceQueryResult query(MethodCatalog catalog, Path moduleRoot, SourceQueryRequest request)`。
- Consumes: Task 4 request/result/Limits、现有 MethodCatalog/SourceAnchor/CallResolutionKind。

- [ ] **Step 1: 写六种 mode 和安全失败测试**

```java
@Test void methodReturnsExactAnchorAndBoundedSourceWindow() { }
@Test void callersAndCalleesAreStableAndDeduplicated() { }
@Test void reachablePathHandlesCyclesAndHonorsDepthNodeAndPathBudgets() { }
@Test void searchSymbolReturnsStablePrefixAndPartialCompleteness() { }
@Test void sourceWindowHonorsLineAndUtf8ByteLimits() { }
@Test void polymorphicCandidateIsNeverReportedAsRuntimeObserved() { }
@Test void catalogPartialPropagatesToResultCompleteness() { }
@Test void absolutePathTraversalSymlinkEscapeAndSensitiveFileAreRejected() { }
@Test void repeatedQueryProducesEquivalentResultAndProvenance() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl static-analysis,case-management,ada-core -am test "-Dtest=ReachablePathFinderTest,BoundedSourceWindowReaderTest,SourceQueryServiceTest,StaticAnalysisApplicationServiceTest,CaseArchiveRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现不可变索引和稳定算法**

`MethodCatalogIndex` 使用按 methodKey 排序的 immutable maps/lists；路径搜索使用有界 BFS，邻接节点按 methodKey/sourceLine/resolutionKind 排序；循环通过当前 path visited set 截断。超预算返回稳定前缀、`PARTIAL` 和 reason code，不扩大预算。

- [ ] **Step 4: 实现源码窗口和 Core 用例**

Reader 只接收 `SourceAnchor` 和 before/after lines，规范化后验证仍位于 module root；读取 UTF-8 有界窗口，计算当前文件 SHA-256。Core 创建 queryId；Repository 以 queryId 为 identity 分别原子追加 request/result、拒绝覆盖并注册 Artifact；结果只返回相对路径和 provenance，不返回绝对路径。

- [ ] **Step 5: 运行 GREEN、模块回归和 Commit**

```powershell
mvn -pl static-analysis,case-management,ada-core -am test
git diff --check
git add static-analysis case-management ada-core
git commit -m "feat: add bounded source queries"
```

### Task 9: 实现三值 Observation Evaluator 和假设 Reducer

**Files:**
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationOperatorEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationOperatorRegistry.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/MethodObservedEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/RecordExistsEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ValueEqualsEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ValueChangedEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/CountCompareEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/PathContainsEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/FailureFingerprintEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceView.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationInputHasher.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationEvaluationSemantics.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/ObservationEvaluator.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/HypothesisEvidenceReducer.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceObligationEvaluator.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/ObservationEvaluatorTest.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/HypothesisEvidenceReducerTest.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceObligationEvaluatorTest.java`

**Interfaces:**
- Produces: `ObservationEvaluation evaluate(ObservationPredicate, EvidenceView)`；`HypothesisEvaluation reduce(HypothesisRecord, List<ObservationPredicate>, List<EvidenceGap>, List<ObservationEvaluation>)`。
- Produces: `List<EvidenceObligation> evaluate(AnalysisControlView, InvestigationState)`。
- Consumes: normalized CodePath/JDWP summaries、Evidence Query semantics、Task 2 eligibility、Task 3/4 contracts。

- [ ] **Step 1: 写每个 operator 的参数化三值测试**

```java
@ParameterizedTest
@MethodSource("allOperatorCases")
void evaluatesEveryOperatorWithTrueFalseAndUnknown(
        ObservationPredicate predicate, EvidenceView evidence, ObservationTruth expected) { }

@Test void completeNoMatchCanProduceFalse() { }
@Test void partialNoMatchAlwaysProducesUnknown() { }
@Test void changedFailureProducesClueOnlyAndDoesNotApplyEffect() { }
@Test void incomparableFailureProducesClueOnlyAndDoesNotApplyEffect() { }
@Test void invalidEvidenceIsRejectedBeforeEvaluation() { }
@Test void samePredicateAndEvidenceHashProduceSameSemanticResult() { }
```

`allOperatorCases()` 对七种 operator 各提供 TRUE/FALSE/UNKNOWN 三个 fixture，共 21 个基础分支。

- [ ] **Step 2: 写 Reducer 状态测试**

```java
@Test void criticalRefuteMakesHypothesisRefuted() { }
@Test void allCriticalSupportAndClosedGapsMakeSupported() { }
@Test void unknownCriticalMakesInconclusive() { }
@Test void corroboratingEvidenceCannotAloneMakeSupportedOrRefuted() { }
@Test void addingUnknownCriticalMovesSupportedToInconclusive() { }
@Test void refutedIsTerminal() { }
@Test void clueOnlyEvaluationNeverChangesStatus() { }
@Test void systemToolAndInvestigationObligationsRemainIndependent() { }
@Test void satisfiedToolObligationCannotCloseUnknownCriticalPredicate() { }
```

- [ ] **Step 3: 运行 RED**

```powershell
mvn -pl evidence-engine -am test "-Dtest=ObservationEvaluatorTest,HypothesisEvidenceReducerTest,EvidenceObligationEvaluatorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现完整 Registry 和纯函数 evaluator**

```java
private static final Map<ObservationOperator, ObservationOperatorEvaluator> EVALUATORS =
        Map.ofEntries(
                Map.entry(METHOD_OBSERVED, new MethodObservedEvaluator()),
                Map.entry(RECORD_EXISTS, new RecordExistsEvaluator()),
                Map.entry(VALUE_EQUALS, new ValueEqualsEvaluator()),
                Map.entry(VALUE_CHANGED, new ValueChangedEvaluator()),
                Map.entry(COUNT_COMPARE, new CountCompareEvaluator()),
                Map.entry(PATH_CONTAINS, new PathContainsEvaluator()),
                Map.entry(FAILURE_FINGERPRINT_MATCHES, new FailureFingerprintEvaluator()));
```

Registry 构造时断言 key set 等于 `EnumSet.allOf(ObservationOperator.class)`。Evaluator 不访问文件/时间/LLM，不复制 Raw 值；UNKNOWN 保留 limitation；只有 confirmation eligible 才 `effectApplied=true`。

Reducer 必须显式使用冻结 Predicate 的角色和 Gap 状态，禁止从 `effectApplied`、limitation 字符串或 ID 命名反推。只有
CRITICAL 且 confirmation eligible 的结果可改变状态；CORROBORATING 仅保留支持/反证引用。

- [ ] **Step 5: 运行 GREEN、属性重复测试和 Commit**

```powershell
mvn -pl evidence-engine -am test
git add evidence-engine
git commit -m "feat: evaluate investigation observations deterministically"
```

### Task 10: 将 CodePath/JDWP Plan 绑定到调查契约

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathCollectionPlan.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/JdwpCollectionPlan.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/collection/codepath-plan-v7.schema.json`
- Create: `schemas/collection/jdwp-plan-v6.schema.json`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanRequest.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpPlanRequest.java`
- Create: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/InvestigationBindingRequest.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanCompiler.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpPlanCompiler.java`
- Review only: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CollectorDebugPlan.java`（外部 Collector v5 协议保持不变）
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CollectorDebugPlanWriter.java`
- Create: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/InvestigationBindingValidator.java`
- Create: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/InvestigationBoundPlanCompilerTest.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/CodePathPlanCompilerTest.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/JdwpPlanCompilerTest.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/CollectorDebugPlanWriterTest.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/CollectorDebugPlanCompatibilityTest.java`
- Modify: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/LauncherCodePathPlan.java`
- Review only: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/CodePathPlanReader.java`（现有严格 Mapper 无需修改）
- Modify: `tools/code-path-tracer-junit-launcher/src/test/java/org/example/algorithmdebug/codepath/launcher/CodePathPlanReaderTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CodePathPlanSummary.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpPlanSummary.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`

**Interfaces:**
- Produces: CodePath v7/JDWP v6 Plan with `InvestigationBinding`；legacy v6/v5 reader adapters。
- Consumes: current `InvestigationState`、Method Catalog、selected methods/tracepoints/projections。

- [ ] **Step 1: 写 Plan 绑定失败测试**

```java
@Test void planRequiresOpenGapExistingHypothesisAndFrozenPredicate() { }
@Test void planRequiresAtLeastOneCriticalPredicate() { }
@Test void planRejectsCrossAnalysisBinding() { }
@Test void predicateMustBeEvaluableFromSelectedMethodAndProjection() { }
@Test void duplicatePredicateIdsAreRejected() { }
@Test void legacyPlansRemainReadableAsLegacyUnstructured() { }
@Test void newWriterNeverWritesLegacyInvestigationIntentAsGate() { }
@Test void launcherReaderPreservesEveryBindingIdentityAndPredicate() { }
@Test void planSummaryExposesBindingWithoutDuplicatingInvestigationState() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -Pcodepath-launcher -pl ada-contracts,debug-plan-engine,case-management,ada-core,:code-path-tracer-junit-launcher -am test "-Dtest=InvestigationBoundPlanCompilerTest,CodePathPlanCompilerTest,JdwpPlanCompilerTest,CollectorDebugPlanWriterTest,CollectorDebugPlanCompatibilityTest,CodePathPlanReaderTest,CaseArchiveRepositoryTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 Plan v7/v6 和 validator**

Validator 校验 identity、Gap 状态、Hypothesis、Predicate 数量/唯一性/冻结状态、CRITICAL 存在、operator 对 Tool 的支持和 projection 可用性。人类说明字段可以保留，但不参与 Evaluation。

- [ ] **Step 4: 实现兼容 reader 和 Archive identity**

旧 Plan 只读并标记 `LEGACY_UNSTRUCTURED`；新写入只生成当前版本。Writer、外部 Launcher reader 和 Core summary 必须无损传递 binding identity/predicate；任何一层缺字段都失败，禁止静默降级。Archive 校验 Plan binding 中所有 ID 属于同一 Analysis，禁止覆盖同一 planId。

- [ ] **Step 5: 运行 GREEN 和 Commit**

```powershell
mvn -Pcodepath-launcher -pl ada-contracts,debug-plan-engine,case-management,ada-core,:code-path-tracer-junit-launcher -am test
git add ada-contracts debug-plan-engine tools/code-path-tracer-junit-launcher case-management ada-core schemas/collection
git commit -m "feat: bind collection plans to investigation predicates"
```

### Task 11: 采集后自动评估并更新 Investigation Ledger

**Files:**
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionPostProcessingService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionPostProcessingResult.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/InvestigationApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionEvidenceViewFactory.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/InvestigationApplicationServiceTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionEvidenceViewFactoryTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/JdwpCollectionApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/StaticAnalysisApplicationServiceTest.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/InvestigationBindingValidator.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/InvestigationBoundPlanCompilerTest.java`

**Interfaces:**
- Produces: `InvestigationState update(InvestigationUpdateCommand)`；采集成功后自动追加 Evaluation/Gap/Hypothesis 系统事件。
- Consumes: Task 5 archive、Task 9 evaluator/reducer、Task 10 Plan binding。

- [ ] **Step 1: 写命令和采集后处理失败测试**

```java
@Test void modelCanAddHypothesisGapAndPredicateButCannotSetStatus() { }
@Test void problemFrameCannotBeModifiedAfterAnalysisBegin() { }
@Test void successfulPlanCreationAutomaticallyMarksGapPlanned() { }
@Test void collectionAutomaticallyEvaluatesEveryBoundPredicateExactlyOnce() { }
@Test void falseCriticalAppendsContradictionAndRefutesHypothesis() { }
@Test void clueOnlyTruthIsArchivedWithoutStateTransition() { }
@Test void evaluatorFailurePreservesCollectionAndReturnsPostProcessingFailure() { }
@Test void unexpectedObservationRequiresNewHypothesisAndCannotRewritePredicate() { }
@Test void evidenceViewUsesOnlyBoundedNormalizedFacts() { }
@Test void planRejectsUnsupportedRecordFieldAndAmbiguousProjection() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl ada-core -am test "-Dtest=InvestigationApplicationServiceTest,CollectionApplicationServiceTest,JdwpCollectionApplicationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 typed command 和系统事件边界**

模型 command 只允许 `ADD_HYPOTHESIS/ADD_EVIDENCE_GAP/REGISTER_PREDICATE/MARK_GAP_UNRESOLVED`。Plan/Collection handler 自动写 `PlanBound/ObservationEvaluated/GapStatusChanged/HypothesisStatusChanged`。任何后处理失败都保留 Raw/Manifest/Validation 并返回结构化 cause。

- [ ] **Step 4: 运行 GREEN 和完整采集模块回归**

```powershell
mvn -pl method-path-codepathtracer,jdwp-collector-adapter,trace-normalizer,trace-validator,evidence-engine,ada-core -am test
```

- [ ] **Step 5: Commit**

```powershell
git add ada-core
git commit -m "feat: update investigation state from collected evidence"
```

### Task 12: 实现 CausalChain 和 ConclusionGate

**Files:**
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionCandidate.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/coordination/ConclusionDecision.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/coordination/conclusion-candidate-v2.schema.json`
- Create: `schemas/coordination/conclusion-decision-v2.schema.json`
- Preserve: `schemas/coordination/conclusion-candidate-v1.schema.json`
- Preserve: `schemas/coordination/conclusion-decision-v1.schema.json`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/coordination/CoordinationSchemaTest.java`
- Create: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/CausalChainEligibilityEvaluator.java`
- Modify: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceSufficiencyEvaluator.java`
- Create: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/CausalChainEligibilityEvaluatorTest.java`
- Modify: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceSufficiencyEvaluatorTest.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ConclusionGate.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/ConclusionGateTest.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionPostProcessingService.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ConclusionDecisionArchive.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ConclusionDecisionArchiveTest.java`
- Create: `docs/development/conclusion-contract-v2-migration.md`

**Interfaces:**
- Produces: `ConclusionDecision evaluate(ConclusionCandidate, AnalysisControlView, InvestigationState)`。
- Consumes: Candidate 内嵌的有界 `causalChains[]`、只读 Reference Catalog 中的 Evidence/Source Query refs、Observation results、eligibility、hypothesis/gap states。
- Contract closure: `ConclusionDecision.allowedActions[]` 返回拒绝后的合法下一步；不得仅返回自然语言提示。

- [ ] **Step 1: 写因果链和等级失败测试**

```java
@Test void confirmedRequiresSymptomRuntimeOrDecisionAndSourceMechanismPath() { }
@Test void sourceOnlyChainIsAtMostBoundedHypothesis() { }
@Test void confirmedRequiresTwoGroundedHypothesesAndOneRefutedAlternative() { }
@Test void openEquivalentAlternativeBlocksConfirmed() { }
@Test void falseCriticalOrUnresolvedContradictionBlocksConfirmed() { }
@Test void changedFailureFingerprintBlocksConfirmedButAllowsBoundedClue() { }
@Test void incompleteCoverageAndUnknownCriticalBlockConfirmed() { }
@Test void insufficientOverallStatusMayStillContainDirectConfirmedFacts() { }
@Test void naturalLanguageClaimMustReferenceAcceptedNodeOrEdge() { }
@Test void rejectedCandidateIsArchivedWithAllowedLevelAndMissingEvidence() { }
@Test void sufficiencyUsesOrthogonalEligibilityAndObligationsWithoutLegacyBoolean() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl evidence-engine,case-management,ada-core -am test "-Dtest=CausalChainEligibilityEvaluatorTest,EvidenceSufficiencyEvaluatorTest,ConclusionGateTest,ConclusionDecisionArchiveTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现纯 eligibility 和 Gate**

Evaluator 验证节点/边引用、关键路径、动态 Evidence、source query refs、反证、替代假设和 gap。Gate 再应用 claim classification、失败指纹、截断、系统/工具/调查义务和整体状态上限。

- [ ] **Step 4: 实现追加 candidate/accepted/rejected**

先原子写 candidate，再写唯一 accepted 或 rejected。Rejected 返回 `allowedStatus`、`reasonCodes`、`missingEvidence`、`allowedActions`；不删除候选或证据。

- [ ] **Step 5: 运行 GREEN 和 Commit**

```powershell
mvn -pl evidence-engine,case-management,ada-core -am test
git add evidence-engine case-management ada-core
git commit -m "feat: gate causal conclusions with counterevidence"
```

### Task 13: 注册 17 个 Action、Policy 和 Core Handler

**Files:**
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionInputs.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionPrerequisites.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ActionPolicySupport.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/LifecycleActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/ReadActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisActionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/InvestigationUpdatePolicy.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/TargetExecutionPolicies.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionHandlers.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CaseApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjector.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseSessionRequest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseSessionService.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/CoreActionRegistryTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/PolicyTestFixtures.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/LifecycleActionPoliciesTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/SourceQueryActionPolicyTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/InvestigationUpdatePolicyTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/RunTestActionPolicyTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/CodePathCollectActionPolicyTest.java`
- Create: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/JdwpCollectActionPolicyTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisStateProjectorTest.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseSessionServiceTest.java`

**Interfaces:**
- Produces: 每个 `AnalysisActionType` 恰好一个 typed binding；`ControlPlaneServices.coordinator()`。
- Consumes: 全部 ApplicationService、Task 7 Coordinator、Task 8/11/12 服务。

- [ ] **Step 1: 写 Registry 和 Policy 矩阵测试**

```java
@Test void everyActionTypeHasExactlyOneBinding() { }
@Test void analysisBeginAtomicallyCreatesProblemFrame() { }
@Test void sourceQueryRequiresCurrentMethodCatalog() { }
@Test void investigationUpdateRejectsDirectStatusOrEvaluationWrite() { }
@Test void planCreateRequiresStructuredBinding() { }
@Test void targetActionsRequireProjectLockAndOperationId() { }
@Test void finalizeDelegatesToConclusionGate() { }
@Test void rejectedPolicyDoesNotInvokeCoreService() { }
```

Policy 分类固定为：READ_ONLY、CASE_WRITE、TARGET_EXECUTION。`SOURCE_QUERY` 是 CASE_WRITE，因为归档 query result；`INVESTIGATION_UPDATE` 和 `ANALYSIS_FINALIZE` 是 CASE_WRITE。

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl case-management,ada-core -am test "-Dtest=CoreActionRegistryTest,InvestigationUpdatePolicyTest,RunTestActionPolicyTest,CodePathCollectActionPolicyTest,JdwpCollectActionPolicyTest,CaseApplicationServiceTest,CaseSessionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 typed inputs 和 bindings**

```java
public final class CoreActionInputs {
    public record AnalysisBegin(ProblemFrame problemFrame,
            Optional<CaseId> existingCaseId, Optional<String> adapterId) { }
    public record SourceQuery(SourceQueryMode mode, Optional<String> methodKey,
            Optional<String> targetMethodKey, Optional<SourceAnchor> sourceAnchor,
            Optional<String> symbol, SourceQueryBudget budget) { }
    public record InvestigationUpdate(InvestigationUpdateCommand command) { }
    public record CodePathPlanCreate(CodePathPlanRequest request) { }
    public record CodePathCollect(PlanId planId) { }
    public record JdwpPlanCreate(JdwpPlanRequest request) { }
    public record JdwpCollect(PlanId planId) { }
    public record AnalysisFinalize(ConclusionCandidate candidate) { }
    private CoreActionInputs() { }
}
```

其余输入使用明确 record；Workspace/Case/Analysis identity 只在 `ActionTarget`，领域命令自身需要身份时必须与 Target
完全一致。`ProblemFrame` 的 Case/Analysis identity 由服务端预分配，不由模型选择。`CaseSessionRequest` 将不可变
`ProblemFrame` 作为创建 Analysis 的必需值，`CaseSessionService` 在同一个 staging/atomic commit 中写 Analysis
manifest 与第一条 `ProblemFrameDefined` 事件；任一写入失败都不得暴露半初始化 Analysis。`ANALYSIS_BEGIN` 使用
“manifest 与 journal 均不存在”的合法 revision 0 引导视图；半初始化或损坏状态仍 fail closed。

Policy 可使用组合根注入的只读 prerequisite port 检查算法输入、当前 Method Catalog 和 Plan 归属；不得读取 Raw
Trace 或执行副作用。Task 13 只构造完整 Registry 并提供 `ControlPlaneServices` 的不可变 Coordinator 绑定接口，
Task 14 在唯一 Runtime 组合根注入 Workspace/Project 路由、状态源、锁、幂等日志和结论目录，不创建第二套 Policy。

- [ ] **Step 4: 接入 ControlPlaneServices 并运行 GREEN**

```powershell
mvn -pl case-management,ada-core -am test
```

Expected: Registry 的 key set 等于全部 Action enum；无 direct ApplicationService 模型入口绕过 Coordinator。

- [ ] **Step 5: Commit**

```powershell
git add case-management ada-core
git commit -m "feat: register evidence-constrained analysis lifecycle"
```

### Task 14: 提取唯一共享 Runtime 组合根

**Files:**
- Modify: `pom.xml`
- Create: `algorithm-debug-runtime/pom.xml`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntime.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrap.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeBootstrapRequest.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeToolchain.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeToolchainResolver.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/CodePathRuntimeFactory.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/JdwpRuntimeFactory.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeCapabilityStatus.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeBootstrapException.java`
- Create: `algorithm-debug-runtime/src/main/java/org/example/algorithmdebug/runtime/RuntimeAnalysisArchiveRouter.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisArchiveSnapshot.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/AnalysisArchiveReader.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/WorkspaceLayout.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionPrerequisites.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/CoreActionHandlers.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/TargetExecutionPolicies.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/WorkspaceExecutionLockManager.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinator.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/coordination/AnalysisPolicyExecutor.java`
- Create: `algorithm-debug-runtime/src/test/java/org/example/algorithmdebug/runtime/AlgorithmDebugRuntimeBootstrapTest.java`
- Create: `algorithm-debug-runtime/src/test/java/org/example/algorithmdebug/runtime/RuntimeToolchainResolverTest.java`
- Create: `algorithm-debug-runtime/src/test/java/org/example/algorithmdebug/runtime/RuntimeAnalysisArchiveRouterTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/AnalysisArchiveReaderTest.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/WorkspaceLayoutTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/OperationIdempotencyServiceTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/AnalysisCoordinatorTest.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/PolicyTestFixtures.java`
- Modify: `ada-core/src/test/java/org/example/algorithmdebug/core/coordination/RunTestActionPolicyTest.java`

**Interfaces:**
- Produces: `AlgorithmDebugRuntime bootstrap(RuntimeBootstrapRequest)`；runtime 暴露唯一 `AnalysisCoordinator` 和 `close()`。
- Consumes: existing adapters/collectors/Core configuration；不依赖 CLI/MCP/Host Adapter。

- [ ] **Step 1: 写组合根失败测试**

```java
@Test void bootstrapCreatesSingleCoordinatorWithAllSeventeenActions() { }
@Test void runtimeSeparatesAgentJavaTargetJavaAndMaven() { }
@Test void missingCollectorReturnsCapabilityFailureNotNullService() { }
@Test void closeIsIdempotentAndReleasesManagedResources() { }
@Test void runtimeModuleHasNoCliMcpOrHostAdapterDependency() { }
@Test void routesStateAndJournalsByProjectIdentity() { }
@Test void analysisBeginControlRecordsDoNotPrecreateTheCaseOrAnalysisDirectory() { }
@Test void analysisBeginRequiresAnImmutableProjectRegistration() { }
@Test void archiveSnapshotUsesTypedControlOwnershipAndNeverReadsRawTrace() { }
@Test void archiveSnapshotRejectsSourceQueryWithoutItsImmutableRequest() { }
@Test void targetExecutionsUseConfiguredTargetJava() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl algorithm-debug-runtime -am test
```

Expected: FAIL because module is absent.

- [ ] **Step 3: 实现模块和 bootstrap**

把 `AdaMain` 中 ServiceLoader、Maven/JDK、CodePath/JDWP、Doctor 和 Core 装配移动到 runtime。Factory 每个只负责一个 collector；缺失能力返回 typed capability status，不创建 null/空实现。Runtime 固定 Workspace 映射但按 `projectId` 惰性路由 Archive；所有路由先验证不可变 Project 注册和非符号链接路径。`AnalysisArchiveReader` 只从有界注册 metadata 与经过 Repository 强校验的 typed 控制文档建立状态/结论目录。Operation Journal、Investigation、Decision、Conclusion 都使用相同 Project 路由。`ANALYSIS_BEGIN` 的 Decision 与 Operation Journal 写入 `projects/{projectId}/control/analysis-begins/cases`，不得在 Handler 原子发布前创建正式 Case/Analysis 目录；其他动作仍写正式 Project Case 根。`ControlPlaneServices` 显式接收 target Java，禁止目标进程退回 Agent Java。

- [ ] **Step 4: 运行 GREEN 和依赖树检查**

```powershell
mvn -pl algorithm-debug-runtime -am test
mvn -pl algorithm-debug-runtime dependency:tree
```

Expected: dependency tree 不含 `algorithm-debug-cli`、`algorithm-debug-mcp-server`、`integrations`。

- [ ] **Step 5: Commit**

```powershell
git add pom.xml algorithm-debug-runtime
git commit -m "refactor: add shared algorithm debug runtime"
```

### Task 15: 让 CLI 复用 Runtime 和 Coordinator

**Files:**
- Modify: `algorithm-debug-cli/pom.xml`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Create: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCoordinatedResultAdapter.java`
- Delete: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/RuntimeToolchain.java`
- Create: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCoordinatorCompatibilityTest.java`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/AdaMainTest.java`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCommandExecutorTest.java`

**Interfaces:**
- Produces: 旧 `ToolResponse 2.0` 兼容输出；模型相关 CLI command 全经 Coordinator。
- Consumes: Task 14 Runtime、Task 3 coordinated result。

- [ ] **Step 1: 写 CLI 兼容和不可绕过测试**

```java
@Test void modelCommandsInvokeCoordinatorExactlyOnce() { }
@Test void managementCommandsRemainDirectAndDoNotPretendToBeAnalysisActions() { }
@Test void rejectedDecisionMapsToStableCliErrorWithoutLosingCause() { }
@Test void targetFailureRemainsSuccessfulCliInvocationWithTargetOutcome() { }
@Test void toolResponseV2GoldenRemainsReadable() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl algorithm-debug-cli -am test "-Dtest=CliCoordinatorCompatibilityTest,AdaMainTest,CliCommandExecutorTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 删除重复装配并实现 adapter**

`AdaMain` 只解析 CLI/config 并调用 Runtime bootstrap；`CliCommandExecutor` 将模型 command 映射为 ActionRequest；`CliCoordinatedResultAdapter` 只做协议形状转换，不复制 Policy 或错误判断。

- [ ] **Step 4: 运行 GREEN 和 CLI smoke**

```powershell
mvn -pl algorithm-debug-cli -am test
.\bin\ada.cmd doctor --json
```

- [ ] **Step 5: Commit**

```powershell
git add algorithm-debug-cli
git commit -m "refactor: route cli analysis through coordinator"
```

### Task 16: 建立 Java stdio MCP Server 生命周期

**Files:**
- Modify: `pom.xml`
- Create: `algorithm-debug-mcp-server/pom.xml`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AlgorithmDebugMcpMain.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpServerBootstrap.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/AlgorithmDebugMcpServer.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpServerLifecycle.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/McpProtocolErrorMapper.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/StdioMcpContractTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpServerLifecycleTest.java`

**Interfaces:**
- Produces: stdio MCP initialize/shutdown、Server identity/capabilities、bounded cancellation/close。
- Consumes: MCP SDK 2.0.1、Task 14 Runtime。

- [ ] **Step 1: 写协议生命周期失败测试**

```java
@Test void initializeNegotiatesStableServerIdentity() { }
@Test void stdoutContainsOnlyProtocolFrames() { }
@Test void logsAndStackTracesGoToStderr() { }
@Test void shutdownRejectsNewCallsAndWaitsBoundedlyForActiveCalls() { }
@Test void cancellationPropagatesToCoordinator() { }
@Test void malformedJsonRpcAndUnknownCapabilityAreProtocolErrors() { }
@Test void oneServerInstanceIsBoundToOneFrozenProjectRoot() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
mvn -pl algorithm-debug-mcp-server -am test "-Dtest=StdioMcpContractTest,McpServerLifecycleTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 stdio Server 和关闭顺序**

使用 `mcp-core` 与 `mcp-json-jackson2`；不引入 Spring。关闭顺序：拒绝新请求 → 传播取消 → 有界等待 → Runtime close → Server close。所有日志使用 stderr/DFX，stdout writer 仅交给 SDK transport。

- [ ] **Step 4: 运行 GREEN 和依赖检查**

```powershell
mvn -pl algorithm-debug-mcp-server -am test
mvn -pl algorithm-debug-mcp-server dependency:tree
```

- [ ] **Step 5: Commit**

```powershell
git add pom.xml algorithm-debug-mcp-server
git commit -m "feat: add java stdio mcp server"
```

### Task 17: 暴露 17 个 Tool、Resource、Prompt 和统一结果

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
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/SourceQueryInput.java`
- Create: `algorithm-debug-mcp-server/src/main/java/org/example/algorithmdebug/mcp/input/InvestigationUpdateInput.java`
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
- Create: `schemas/mcp/tools/source-query-v1.schema.json`
- Create: `schemas/mcp/tools/investigation-update-v1.schema.json`
- Create: `schemas/mcp/tools/codepath-plan-create-v1.schema.json`
- Create: `schemas/mcp/tools/codepath-collect-v1.schema.json`
- Create: `schemas/mcp/tools/jdwp-plan-create-v1.schema.json`
- Create: `schemas/mcp/tools/jdwp-collect-v1.schema.json`
- Create: `schemas/mcp/tools/artifact-read-v1.schema.json`
- Create: `schemas/mcp/tools/evidence-query-v1.schema.json`
- Create: `schemas/mcp/tools/analysis-status-v1.schema.json`
- Create: `schemas/mcp/tools/analysis-finalize-v1.schema.json`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpToolCatalogTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpToolDispatcherTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpRequestContextResolverTest.java`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpResultMapperTest.java`

**Interfaces:**
- Produces: 17 Tools、4 Resources、3 Prompts；所有 `tools/call` 只走 Dispatcher → Coordinator。
- Consumes: Task 13 bindings、Task 16 Server、root Schema resources。

- [ ] **Step 1: 写 Catalog 完整性和 Schema 测试**

```java
@Test void catalogActionsEqualAllAnalysisActionTypes() { }
@Test void toolNamesSchemasAndBindingsAreUniqueAndComplete() { }
@Test void sourceQueryAndInvestigationUpdateExposeTypedSchemas() { }
@Test void analysisBeginRequiresProblemFrame() { }
@Test void unknownFieldsOversizedPayloadsAndIllegalPathsAreRejected() { }
@Test void schemaLimitsEqualJavaLimitConstants() { }
```

输入类型包括 `SourceQueryInput`、`InvestigationUpdateInput`、`AnalysisStatusInput`、`AnalysisFinalizeInput`，以及现有 13 个工具对应 record；不得使用万能 action 字符串或大联合 Map。

- [ ] **Step 2: 写 Context/Mapper 错误分层测试**

```java
@Test void rootsAndFrozenProjectMustResolveToSameCanonicalPath() { }
@Test void windowsCaseUnicodeSymlinkAndTraversalAreHandledSafely() { }
@Test void targetUtFailureIsNotMcpProtocolError() { }
@Test void coordinatorRejectionIsStructuredSuccessWithRejectedOutcome() { }
@Test void badSchemaUnknownToolAndInternalServerFailureAreProtocolErrors() { }
@Test void oversizedResultReturnsBoundedSummaryAndArtifactReference() { }
```

- [ ] **Step 3: 运行 RED**

```powershell
mvn -pl algorithm-debug-mcp-server -am test "-Dtest=McpToolCatalogTest,McpToolDispatcherTest,McpRequestContextResolverTest,McpResultMapperTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 4: 实现 Catalog、Dispatcher、Resources 和 Prompts**

Resource 固定为 `ada://agent/manifest`、`ada://agent/capabilities`、`ada://cases/{caseId}/digest`、`ada://cases/{caseId}/analyses/{analysisId}/status`；Prompt 固定为 `algorithm-debug/start`、`algorithm-debug/continue`、`algorithm-debug/explain-evidence`。Schema 从根目录复制进 JAR，不维护第二份。

- [ ] **Step 5: 运行 GREEN 和协议回归**

```powershell
mvn -pl algorithm-debug-mcp-server -am test
```

- [ ] **Step 6: Commit**

```powershell
git add algorithm-debug-mcp-server schemas/mcp
git commit -m "feat: expose evidence-constrained mcp capabilities"
```

### Task 18: 建立 Canonical Agent 包、Qwen Adapter 和可安装产物

**Files:**
- Create: `agent-definition/algorithm-debug-agent-v1.json`
- Create: `agent-definition/system-prompt-v1.md`
- Create: `agent-definition/completion-contract-v1.schema.json`
- Create: `agent-definition/README.md`
- Create: `agent-definition/test/agent-definition.test.mjs`
- Create: `schemas/agent/algorithm-debug-agent-v1.schema.json`
- Create: `schemas/agent/capability-manifest-v1.schema.json`
- Create: `integrations/host-adapter-kit/README.md`
- Create: `integrations/host-adapter-kit/schemas/host-adapter-manifest-v1.schema.json`
- Create: `integrations/host-adapter-kit/scripts/render-host-config.mjs`
- Create: `integrations/host-adapter-kit/test/render-host-config.test.mjs`
- Create: `integrations/qwen-cli/adapter-manifest.json`
- Create: `integrations/qwen-cli/templates/algorithm-debug-agent.md`
- Create: `integrations/qwen-cli/templates/mcp-server.json`
- Create: `integrations/qwen-cli/templates/extension.json`
- Create: `integrations/qwen-cli/install.ps1`
- Create: `integrations/qwen-cli/check.ps1`
- Create: `integrations/qwen-cli/uninstall.ps1`
- Create: `integrations/qwen-cli/test/installer.test.mjs`
- Create: `config/mcp-agent-settings.json`
- Create: `schemas/config/mcp-agent-settings-v1.schema.json`
- Create: `bin/ada-mcp.cmd`
- Modify: `bin/README.md`
- Modify: `config/README.md`
- Modify: `scripts/build-agent.ps1`
- Modify: `skills/algorithm-debug/SKILL.md`
- Create: `THIRD_PARTY_NOTICES.md`
- Create: `algorithm-debug-mcp-server/src/test/java/org/example/algorithmdebug/mcp/McpPackagingTest.java`

**Interfaces:**
- Produces: 宿主无关 Agent Definition、Qwen Extension、MCP executable JAR、安装/检查/卸载。
- Consumes: 17 Tool capability manifest、Canonical Prompt、Runtime/MCP binaries。

- [ ] **Step 1: 写 Agent/Adapter/Packaging 失败测试**

```javascript
test("generated host profile references one MCP server and no copied tool schema", () => {});
test("prompt and completion contract hashes match the agent definition", () => {});
test("knowledge directory is optional and absence does not fail generation", () => {});
test("install check reinstall and uninstall are idempotent and preserve workspace", () => {});
```

```java
@Test void packagedJarContainsAllSchemasPromptAndServerMainClass() { }
@Test void packageContainsNoCredentialsOrAbsoluteDeveloperPaths() { }
```

- [ ] **Step 2: 运行 RED**

```powershell
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs
mvn -pl algorithm-debug-mcp-server -am test "-Dtest=McpPackagingTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

- [ ] **Step 3: 实现 Canonical Prompt 和薄 Adapter**

Prompt 明确：知识仅为 hint；源码关系不是运行事实；每轮 Plan 绑定 Gap/Hypothesis/Predicate；FALSE/UNKNOWN 必须遵守；最终自然语言 claim 只能引用已接受 CausalChain。`agent-definition/completion-contract-v1.schema.json` 是完成契约唯一正文；`schemas/agent` 只保存 Agent Definition 与 capability manifest 的不同契约，不复制完成契约。Adapter 只渲染宿主配置和权限，不包含业务规则或 Schema 副本。

- [ ] **Step 4: 实现打包和安装生命周期**

`ada-mcp.cmd` 只允许固定 Main class 和受限配置；build 脚本校验 Java CLI、CodePath Launcher、JDWP Collector、MCP Server 四类产物及 hashes。安装使用 staging + 原子切换，卸载按 ownership manifest 删除 hash 未变化资产。

- [ ] **Step 5: 运行 GREEN 和安装验证**

```powershell
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs
mvn -pl algorithm-debug-mcp-server -am test
.\scripts\build-agent.ps1
.\integrations\qwen-cli\install.ps1 -RepositoryRoot . -Scope TestProfile
.\integrations\qwen-cli\check.ps1 -RepositoryRoot . -Scope TestProfile
.\integrations\qwen-cli\uninstall.ps1 -RepositoryRoot . -Scope TestProfile
```

- [ ] **Step 6: Commit**

```powershell
git add agent-definition integrations/host-adapter-kit integrations/qwen-cli config schemas/agent schemas/config bin scripts/build-agent.ps1 skills/algorithm-debug/SKILL.md THIRD_PARTY_NOTICES.md algorithm-debug-mcp-server
git commit -m "feat: package portable algorithm debug subagent"
```

### Task 19: 用真实 DeepSeek Harness 验证第二宿主可移植性

**Files:**
- Create: `integrations/deepseek-harness/adapter-manifest.json`
- Create: `integrations/deepseek-harness/templates/algorithm-debug-agent.md`
- Create: `integrations/deepseek-harness/templates/mcp-server.json`
- Create: `integrations/deepseek-harness/install.ps1`
- Create: `integrations/deepseek-harness/check.ps1`
- Create: `integrations/deepseek-harness/uninstall.ps1`
- Create: `integrations/deepseek-harness/test/installer.test.mjs`
- Create: `integrations/deepseek-harness/test/host-smoke.test.mjs`

**Interfaces:**
- Produces: 只负责 MCP 注册、子 Agent Profile、权限和生命周期的第二薄 Adapter。
- Consumes: Task 1 记录的真实 DeepSeek Harness 契约、Task 18 Host Adapter Kit 与同一 `ada-mcp.cmd`；不修改 Core、Coordinator、Catalog、Schema 或 Canonical Prompt。

- [ ] **Step 1: 验证真实宿主前置条件并写 RED**

先从 Task 1 baseline 读取已验证的 executable/version/config/profile/stdio MCP 契约。若该契约缺失或无法在当前环境执行，本 Task 立即停止并保持未完成；不得猜测配置格式，也不得用自造 fixture 冒充宿主兼容。

```javascript
test("adapter manifest matches the verified DeepSeek Harness contract", () => {});
test("install check reinstall and uninstall preserve unrelated host config", () => {});
test("real host starts the canonical stdio server and lists the canonical catalog", () => {});
test("adapter contains no tool schema evidence rule or prompt copy", () => {});
```

```powershell
node --test integrations/deepseek-harness/test/*.test.mjs
```

- [ ] **Step 2: 实现薄 Adapter**

所有宿主差异只能进入该目录的 manifest/template/lifecycle scripts；Agent 指令引用或由 Canonical Prompt hash 受控生成，MCP command 固定指向同一 `bin/ada-mcp.cmd`。安装使用 staging + 原子替换和 ownership manifest；卸载只删除由本 Adapter 创建且 hash 未变化的内容。

- [ ] **Step 3: 运行真实宿主 GREEN**

```powershell
node --test integrations/deepseek-harness/test/*.test.mjs
.\integrations\deepseek-harness\install.ps1 -RepositoryRoot . -Scope TestProfile
.\integrations\deepseek-harness\check.ps1 -RepositoryRoot . -Scope TestProfile -RequireHostSmoke
.\integrations\deepseek-harness\uninstall.ps1 -RepositoryRoot . -Scope TestProfile
git diff --check
```

- [ ] **Step 4: Commit**

```powershell
git add integrations/deepseek-harness
git commit -m "feat: adapt algorithm debug agent to deepseek harness"
```

### Task 20: 完整集成、收敛 Eval、文档和最终代码审计

**Files:**
- Modify: `integration-tests/pom.xml`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/McpAnalysisLifecycleIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/McpTargetExecutionConcurrencyIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/SuccessfulUtEvidenceIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/FailedUtFingerprintIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/SourceToRuntimeCausalLoopIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/KnowledgeIndependentCoreIT.java`
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/ContradictoryHypothesisIT.java`
- Create: `agent-evals/convergence-grade.mjs`
- Create: `agent-evals/suites/evidence-convergence-smoke.json`
- Create: `agent-evals/suites/evidence-convergence-quality.json`
- Modify: `agent-evals/run.mjs`
- Modify: `agent-evals/README.md`
- Modify: `README.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/current-capabilities.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `docs/architecture/tool-validation-baseline.md`
- Create: `docs/audits/2026-09-28-evidence-constrained-mcp-implementation-audit.md`

**Interfaces:**
- Produces: 可复现 E2E、性能、收敛评分、requirement-to-code-to-test 矩阵和最终审计。
- Consumes: Tasks 1-19 的全部生产和测试产物。

- [ ] **Step 1: 先写七条真实集成测试并确认 RED**

Lifecycle 必须执行：begin(ProblemFrame) → input → run → static → source query → hypotheses/gaps/predicates → plan → collect → automatic evaluation → status → causal finalize。Concurrency 启动两个独立 Server。Contradictory 测试证明 critical FALSE 阻止错误根因。KnowledgeIndependent 在不存在 `knowledge` 目录时完成闭环。

```powershell
mvn -pl integration-tests -am verify "-Dit.test=McpAnalysisLifecycleIT,McpTargetExecutionConcurrencyIT,SuccessfulUtEvidenceIT,FailedUtFingerprintIT,SourceToRuntimeCausalLoopIT,KnowledgeIndependentCoreIT,ContradictoryHypothesisIT" "-Dfailsafe.failIfNoSpecifiedTests=false"
```

Expected: 在集成 glue 和 fixtures 完成前出现精确 RED；不得通过删除 E2E 断言换取通过。

- [ ] **Step 2: 完成 fixture/glue 并运行全 Reactor**

```powershell
mvn -Pcodepath-launcher test
mvn -Pcodepath-launcher verify
.\scripts\build-agent.ps1
```

Expected: Surefire、Failsafe、Launcher 打包和 MCP packaging 全绿。

- [ ] **Step 3: 建立收敛 Gold/negative Case**

Quality Suite 至少包含 10 种不同根因机制，并为每类提供：原问、同义改写、无关源码/变量、误导知识、Evidence 乱序、矛盾 Evidence、缺失 Evidence。Grader 读取结构化 completion，不用答案正则代替因果验证。

```javascript
export function gradeConvergence(runs, gold) {
  return {
    hardInvariantViolations: countInvariantViolations(runs),
    falseConfirmedClaims: countFalseConfirmed(runs, gold),
    terminalAgreement: terminalAgreement(runs),
    causalChainF1: causalChainF1(runs, gold),
    evidenceJaccard: evidenceJaccard(runs),
    distractorCaptureRate: distractorCaptureRate(runs, gold)
  };
}
```

- [ ] **Step 4: 执行 5 次收敛 Eval 和门禁**

```powershell
node agent-evals/run.mjs --suite agent-evals/suites/evidence-convergence-smoke.json --repetitions 5
node agent-evals/run.mjs --suite agent-evals/suites/evidence-convergence-quality.json --repetitions 5
```

Required: hard violations = 0；false confirmed = 0；证据不足强行确认 = 0；反证后确认 = 0；terminal/root-cause agreement ≥ 0.90；causal chain F1 ≥ 0.85；evidence Jaccard ≥ 0.70；distractor capture ≤ 0.10。

- [ ] **Step 5: 执行性能、故障和安全验证**

记录 MCP 冷启动、热 `analysis_status`、10,000 Artifact/Event 状态投影、Source Query 上限、双进程锁、关闭等待、目标 JVM 清理。注入坏 Schema/hash、半写文件、子 JVM 崩溃、Collector 超时、MCP 断连、Unicode/长路径/重解析点。

- [ ] **Step 6: 执行完整代码审计并修复全部阻断项**

审计文档逐条记录：spec requirement → task/commit → production files → tests → command/result。额外扫描：模块依赖方向、重复 Policy/常量/错误码、超长类方法、公共 Javadoc、cause、资源关闭、线程/进程、Schema compatibility、敏感日志、未使用代码、空实现、TODO、注释代码、测试专用分支和文档偏差。

```powershell
rg -n "TODO|FIXME|System\.out|printStackTrace|catch\s*\([^)]*\)\s*\{\s*\}" --glob "*.java" .
git diff --check
git status --short
```

P0/P1 和范围内 P2 必须以新的失败测试复现后修复，并按受影响 Task 单独提交，不得塞入最终文档提交；修复后重新运行 Steps 2-5，未修复项阻断完成声明。

- [ ] **Step 7: 更新文档并请求最终代码审查**

README、能力、架构、工作流、验证基线必须与 17 Tool、Source Query、Investigation、Predicate、CausalChain、知识可选和新 Artifact 布局一致。使用 `superpowers:requesting-code-review` 做 whole-branch review；修复所有 P0/P1 和范围内 P2。

- [ ] **Step 8: 最终全量验证和 Commit**

```powershell
mvn -Pcodepath-launcher verify
node --test agent-definition/test/*.test.mjs integrations/host-adapter-kit/test/*.test.mjs integrations/qwen-cli/test/*.test.mjs integrations/deepseek-harness/test/*.test.mjs agent-evals/test/*.test.mjs
.\scripts\build-agent.ps1
git diff --check
```

```powershell
git add README.md docs integration-tests agent-evals
git commit -m "docs: audit evidence-constrained mcp implementation"
```

## Definition of Done

- Evidence 可读性、完整性、失败基准、指纹、义务和确认资格正交；成功 UT 无普通 Run 不丢数据，失败 UT 只有 MATCHED 可确认。
- 17 个 MCP Tool 从唯一 Catalog 暴露；所有模型动作不可绕过 Coordinator；CLI/MCP 复用一个 Runtime 组合根。
- `source_query` 六种 mode 有界、可追溯、安全、稳定，不把静态关系冒充运行事实。
- Problem Frame 唯一；Hypothesis/Gap/Predicate/Evaluation 追加保存；模型不能直接写状态或修改 Predicate。
- 七种 Predicate 全部实现 TRUE/FALSE/UNKNOWN；PARTIAL 无命中为 UNKNOWN；CLUE_ONLY 不应用 effect；REFUTED 不恢复。
- CodePath v7/JDWP v6 Plan 强制绑定开放 Gap、Hypothesis 和可评估 Predicate；旧 Plan 只读且不自动获得确认资格。
- ConclusionGate 检查 CausalChain、动态引用、反证、替代假设、失败指纹和截断；无足够证据稳定结束为 bounded/insufficient。
- 无知识目录可完成完整闭环；正确或误导知识都不能绕过证据门禁。
- Operation/Investigation/Conclusion 追加式归档可重启恢复；两个 Server 对同一 Project 的目标执行互斥；不确定操作不自动重跑。
- MCP initialize/list/call/resource/prompt/shutdown、stdout 纯净性、取消、错误分层、Schema 和预算全部通过。
- Qwen Adapter 薄且可安装/检查/重装/卸载，不复制 Tool Schema、Prompt 或业务规则。
- DeepSeek Harness Adapter 基于已记录的真实宿主契约完成同样的薄适配，并由真实宿主 smoke 证明可启动同一 MCP Server；契约不可获得时不得宣称整体完成。
- 没有魔鬼规则常量、boolean 多义、God Class、循环依赖、空实现、占位接口、永久补丁、吞异常或测试专用生产分支。
- Maven test/verify、Node tests、packaging、7 条真实 E2E、10 机制 × 5 次收敛 Eval、性能和故障注入达到批准门槛。
- 最终审计具有完整 requirement-to-code-to-test 映射；无 P0/P1/范围内 P2；用户 `docs/sharing/` 保持未修改。

## Spec Coverage

| Spec 0.4 要求 | 实施任务 |
|---|---|
| 成功/失败 UT Evidence 语义 | Task 2 |
| Coordination 契约、Action、Result | Task 3 |
| Investigation/Source/Causal contracts 和唯一 Limits | Task 4 |
| 追加 Operation/Decision/Investigation 归档 | Task 5 |
| 跨进程锁和可恢复状态投影 | Task 6 |
| Coordinator 唯一模板、幂等、取消 | Task 7 |
| 六种有界 Source Query | Task 8 |
| 七种三值 Observation 和 Reducer | Task 9 |
| CodePath v7/JDWP v6 调查绑定 | Task 10 |
| 采集后自动 Evaluation 和 Ledger 更新 | Task 11 |
| CausalChain 与 ConclusionGate | Task 12 |
| 17 个 Action/Policy/Handler | Task 13 |
| Runtime 唯一组合根 | Task 14 |
| CLI 兼容且不可绕过 | Task 15 |
| Java stdio MCP 生命周期 | Task 16 |
| 17 Tool/Resource/Prompt/Schema | Task 17 |
| Canonical Agent、Qwen、打包和知识可选 | Task 18 |
| 第二真实宿主且不修改 Core/Coordinator/Catalog | Task 19 |
| E2E、收敛 Eval、性能、安全、文档和完整审计 | Task 20 |
