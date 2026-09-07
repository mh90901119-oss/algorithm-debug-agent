# Dynamic Evidence Discovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不编码目标算法业务语义的前提下，使大模型能够从大型CodePath/JDWP证据中通过概览、过滤、统计、窗口和变化查询快速定位关键因果记录。

**Architecture:** 保留现有Collector、Normalizer、Evidence和Workspace边界，升级CodePath Scope过滤和Invocation关系，并将现有`evidence_query`升级为五个互斥的有界查询模式。所有查询均基于已注册且SHA校验通过的Artifact，返回源覆盖和查询覆盖；LLM依据Skill解释业务语义并制定增量Plan。

**Tech Stack:** Java 21、Maven、JUnit 5、Jackson、JSON/JSONL、TypeScript/OpenCode Custom Tool、Node Test Runner

**Spec:** `docs/designs/2026-09-05-dynamic-evidence-discovery-design.md`

**Execution status:** Completed on 2026-09-05. The implementation and verification evidence is recorded in
`docs/audits/2026-09-05-dynamic-evidence-discovery-final-audit.md`.

## Global Constraints

- 不修改目标算法生产源码，不在Java代码中引入Wafer、Job、腔室或调度策略语义。
- 不增加数据库、向量库、Embedding、图数据库、常驻服务、文件锁或跨会话协调。
- Raw Trace保持只读；Query结果临时返回，不新增无人消费的Workspace文件。
- 新Schema使用主版本升级；Invocation v1保持只读兼容，不迁移历史Workspace。
- CodePath和JDWP每次采集继续遵守现有事件数、字节数、命中数和超时预算。
- 所有Tool错误返回有界英文恢复信息，完整cause仅写DFX日志。
- 每个行为变更遵循Red-Green-Refactor；跨模块完成后执行根项目测试和真实OpenCode Eval。

---

## File Map

| 文件/模块 | 计划职责 |
|---|---|
| `ada-contracts/.../CodePathScopeCondition.java` | CodePath Scope参数条件 |
| `ada-contracts/.../CodePathScalarType.java` | CodePath条件的通用标量类型 |
| `ada-contracts/.../EvidenceQueryMode.java` | 五个互斥查询模式 |
| `ada-contracts/.../EvidenceQueryRequest.java` | v2结构化查询请求 |
| `ada-contracts/.../EvidenceValuePredicate.java` | 多投影AND谓词 |
| `ada-contracts/.../EvidenceQueryContext.java` | Plan、覆盖、维度和语义边界 |
| `ada-contracts/.../EvidenceQueryResult.java` | v2查询结果和分支状态 |
| `debug-plan-engine/.../CodePathPlanCompiler.java` | Scope条件绑定到Method Catalog投影 |
| `tools/code-path-tracer-junit-launcher/.../PlannedTraceEventGenerator.java` | Scope进入时条件过滤和计数 |
| `trace-normalizer/.../CodePathInvocationDeriver.java` | 最近已选择父调用关系 |
| `case-management/.../RegisteredEvidenceQuery.java` | 保持单一公共查询入口 |
| `case-management/.../EvidenceQueryContextResolver.java` | 从Artifact解析Plan/Manifest/Evidence上下文 |
| `case-management/.../CodePathEvidenceQuery.java` | 流式SUMMARY/FILTER/WINDOW/COUNT |
| `case-management/.../JdwpEvidenceQuery.java` | SUMMARY/FILTER/WINDOW/COUNT/CHANGES |
| `algorithm-debug-cli/...` | v2请求解析和结构化错误映射 |
| `integrations/opencode/...` | 模型可见Tool Schema、互斥分支和临时请求适配 |
| `skills/algorithm-debug/SKILL.md` | 先概览、再缩小、后深采集的因果流程 |
| `agent-evals/suites/quality-50.json` | 大规模证据与跨实体因果Eval输入 |
| `agent-evals/grade.mjs` | Tool顺序、Evidence lineage和回答行为判定 |

### Task 1: 锁定Query v2和CodePath v5契约

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceQueryMode.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceQueryOutcome.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceValuePredicate.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceQueryRequest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceQueryContext.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathScopeCondition.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathScalarType.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/EvidenceQueryResult.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathCollectionPlan.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Modify: `schemas/tool/evidence-query-result-v1.schema.json` by replacing it with `schemas/tool/evidence-query-result-v2.schema.json`
- Create: `schemas/collection/codepath-plan-v5.schema.json`
- Create: `schemas/trace/codepath-invocation-v2.schema.json`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/EvidenceQueryContractsTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/EvidenceQueryJsonTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CodePathCollectionPlanJsonTest.java`

**Interfaces:**
- Produces: `EvidenceQueryRequest(mode, artifactId, methodRef, tracepointId, predicates, sequence range, anchor, window, groupBy, valueNames, offset, limit, maxBytes)`.
- Produces: `EvidenceQueryResult` v2 containing identity, `sourceCoverage`, `queryMoreAvailable`, context and bounded JSONL rows.
- Produces: `CodePathScopeCondition(projectionName, expectedType, expectedValue)` with exact scalar equality.

- [ ] **Step 1: Add failing contract tests for every valid and invalid mode combination**

```java
assertDoesNotThrow(() -> EvidenceQueryRequest.filter(artifactId, predicates));
assertThrows(IllegalArgumentException.class,
        () -> EvidenceQueryRequest.window(artifactId, null, 10, 10));
assertThrows(IllegalArgumentException.class,
        () -> EvidenceQueryRequest.changes(artifactId, null, List.of("score")));
```

- [ ] **Step 2: Run the contract tests and confirm RED**

Run: `mvn -pl ada-contracts -am -Dtest=EvidenceQueryContractsTest,CodePathCollectionPlanJsonTest test`

Expected: FAIL because v2 types and Schema versions do not exist.

- [ ] **Step 3: Implement immutable contracts and explicit branch validation**

```java
public enum EvidenceQueryMode { SUMMARY, FILTER, WINDOW, COUNT, CHANGES }
public enum EvidenceQueryOutcome { MATCHED, NO_MATCH }
public enum CodePathScalarType { STRING, INTEGER, DECIMAL, BOOLEAN, NULL }
```

Validation must encode the full matrix from the Spec: SUMMARY accepts no filters; WINDOW requires one anchor; CHANGES requires one Tracepoint and value names; all arrays and text are bounded.

- [ ] **Step 4: Add and validate Schema v2/v5 examples**

Expected: JSON round trips preserve mode-specific fields and reject unknown properties.

- [ ] **Step 5: Run module tests and refactor duplicated validation**

Run: `mvn -pl ada-contracts -am test`

Expected: PASS.

- [ ] **Step 6: Commit the contract boundary**

```powershell
git add ada-contracts schemas
git commit -m "feat: define dynamic evidence query contracts"
```

### Task 2: 编译CodePath Scope参数条件

**Files:**
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanRequest.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanCompiler.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathProjectionRequest.java`
- Modify: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/CodePathPlanCompilerTest.java`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `integrations/opencode/test/tool-runtime.test.mjs`

**Interfaces:**
- Consumes: Scope method and projection definitions from Method Catalog and Task 1 contracts.
- Produces: CodePath Plan v5 whose Scope conditions only reference existing ARGUMENT projections.

- [ ] **Step 1: Add failing compiler tests**

```java
assertThrows(PlanCompilationException.class,
        () -> compile(conditionWithoutScopeMethod));
assertThrows(PlanCompilationException.class,
        () -> compile(conditionReferencingReturnProjection));
assertEquals("entityId", compile(validRequest).scopeConditions().getFirst().projectionName());
```

- [ ] **Step 2: Run RED tests**

Run: `mvn -pl debug-plan-engine -am -Dtest=CodePathPlanCompilerTest test`

- [ ] **Step 3: Compile conditions against the selected Scope method**

Compiler checks projection existence, ARGUMENT source, scalar type/value and maximum four AND conditions. It does not inspect business names.

- [ ] **Step 4: Make OpenCode request generation preserve exact typed conditions**

```javascript
scopeConditions: input.scopeConditions ?? []
```

The Adapter supplies no guessed entity names or values.

- [ ] **Step 5: Run Java and Node tests**

Run: `mvn -pl debug-plan-engine -am test`

Run: `node --test integrations/opencode/test/tool-runtime.test.mjs`

- [ ] **Step 6: Commit Plan compilation**

```powershell
git add debug-plan-engine integrations/opencode
git commit -m "feat: compile codepath scope conditions"
```

### Task 3: 在CodePath Launcher执行有界Scope过滤

**Files:**
- Modify: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/LauncherCodePathPlan.java`
- Modify: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/CodePathPlanReader.java`
- Modify: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/PlannedTraceEventGenerator.java`
- Modify: `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/LauncherSummary.java`
- Modify: `method-path-spi/src/main/java/org/example/algorithmdebug/methodpath/MethodPathManifest.java`
- Modify: `schemas/collection/method-path-manifest-v3.schema.json` by adding a new versioned replacement
- Test: `tools/code-path-tracer-junit-launcher/src/test/java/org/example/algorithmdebug/codepath/launcher/PlannedTraceEventGeneratorTest.java`
- Test: `tools/code-path-tracer-junit-launcher/src/test/java/org/example/algorithmdebug/codepath/launcher/ExternalJUnitTraceLauncherTest.java`
- Test: `method-path-spi/src/test/java/org/example/algorithmdebug/methodpath/MethodPathCollectionContractsTest.java`

**Interfaces:**
- Consumes: CodePath Plan v5.
- Produces: Structurally complete selected-method events only inside matched Scope invocations plus observed/matched/captured/unavailable counters.

- [ ] **Step 1: Add failing event-generation tests for matched, non-matched and unavailable Scope conditions**

```java
generator.onEnter(scopeMethod, arguments("entity-2"));
generator.onEnter(selectedChild, arguments());
generator.onExit(selectedChild, null);
generator.onExit(scopeMethod, null);
assertThat(events).isEmpty();
assertEquals(1, summary.observedScopeInvocations());
```

- [ ] **Step 2: Add a failing nested/recursive Scope test**

The test requires a decision stack so one Scope exit cannot close a different invocation.

- [ ] **Step 3: Run RED launcher tests**

Run: `mvn -pl tools/code-path-tracer-junit-launcher -am -Dtest=PlannedTraceEventGeneratorTest,ExternalJUnitTraceLauncherTest test`

- [ ] **Step 4: Implement Scope decision stack and counters**

Evaluate conditions only at Scope enter, copy only scalar projections, retain matched Scope through its corresponding exit, and never invoke getters or arbitrary expressions.

- [ ] **Step 5: Preserve failure and budget behavior**

Write failure still stops event formatting immediately; timeout and maxEvents remain authoritative. Condition-unavailable details are bounded and included in the Manifest.

- [ ] **Step 6: Run launcher/SPI tests and real fixture smoke**

Run: `mvn -Pcodepath-launcher -pl tools/code-path-tracer-junit-launcher,method-path-codepathtracer -am test`

- [ ] **Step 7: Commit Scope runtime filtering**

```powershell
git add tools/code-path-tracer-junit-launcher method-path-spi schemas/collection
git commit -m "feat: filter codepath scope invocations"
```

### Task 4: 生成最近已选择父调用关系

**Files:**
- Modify: `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/CodePathInvocationDeriver.java`
- Modify: `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/MethodPathNormalizer.java`
- Modify: `schemas/trace/codepath-invocation-v2.schema.json`
- Test: create or extend the CodePath invocation derivation test under `trace-normalizer/src/test/java/org/example/algorithmdebug/normalizer/`
- Test: `trace-normalizer/src/test/java/org/example/algorithmdebug/normalizer/MethodPathNormalizerTest.java`

**Interfaces:**
- Consumes: Raw selected-method enter/exit stack.
- Produces: Invocation v2 with nullable `parentSelectedEnterEventId`.

- [ ] **Step 1: Add failing parent-relation tests**

```java
assertNull(root.parentSelectedEnterEventId());
assertEquals(root.enterEventId(), child.parentSelectedEnterEventId());
```

Include a fixture with an unselected JVM method between two selected methods and assert the field means nearest selected parent, not direct caller.

- [ ] **Step 2: Run RED normalizer tests**

Run: `mvn -pl trace-normalizer -am test`

- [ ] **Step 3: Store parent enterEventId in each open invocation and emit v2 rows**

Do not generate a second Invocation ID. Preserve current structural-incomplete and output-budget limitations.

- [ ] **Step 4: Add v1 read compatibility fixture**

The Query layer may read v1 records but must report `parentRelationAvailable=false`.

- [ ] **Step 5: Run normalizer and Schema tests**

Run: `mvn -pl trace-normalizer,ada-contracts -am test`

- [ ] **Step 6: Commit invocation correlation**

```powershell
git add trace-normalizer schemas/trace ada-contracts
git commit -m "feat: correlate selected codepath calls"
```

### Task 5: 解析自描述查询上下文

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/EvidenceQueryContextResolver.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/EvidenceQueryContextResolverTest.java`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`

**Interfaces:**
- Consumes: Registered ArtifactReference and immutable Collection/Plan/Manifest/Evidence documents.
- Produces: `EvidenceQueryContext` with exact identity, intent, dimensions, coverage, limitations and supported modes.

- [ ] **Step 1: Add failing context resolution tests for CodePath and JDWP**

```java
var context = resolver.resolve(caseId, registeredArtifact);
assertEquals(planId, context.planId());
assertEquals(hypothesis, context.intent().hypothesis());
assertEquals(EvidenceSourceCoverage.PARTIAL, truncatedContext.sourceCoverage());
```

- [ ] **Step 2: Add failing identity-mismatch and tamper tests**

Any disagreement among Artifact path, request, plan, manifest and Evidence must return one structured Workspace error.

- [ ] **Step 3: Run RED case-management tests**

Run: `mvn -pl case-management -am -Dtest=EvidenceQueryContextResolverTest test`

- [ ] **Step 4: Implement bounded context resolution using existing archive documents**

Do not infer identity from an unregistered arbitrary path. Do not create or update any Workspace file.

- [ ] **Step 5: Run case-management tests**

Run: `mvn -pl case-management -am test`

- [ ] **Step 6: Commit query context resolution**

```powershell
git add case-management
git commit -m "feat: resolve dynamic evidence query context"
```

### Task 6: 实现五个有界Evidence Query分支

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CodePathEvidenceQuery.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/JdwpEvidenceQuery.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/EvidenceQueryAccumulator.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/RegisteredEvidenceQuery.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/RegisteredEvidenceQueryTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/CodePathEvidenceQueryTest.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/JdwpEvidenceQueryTest.java`

**Interfaces:**
- Consumes: Task 1 request, Task 5 context and verified Artifact.
- Produces: Mode-specific bounded JSONL rows plus independent source/query coverage.

- [ ] **Step 1: Add failing SUMMARY tests**

Assert that methods, Tracepoints, projection names, intent, limitations and supported modes are returned without raw records.

- [ ] **Step 2: Add failing FILTER multi-predicate tests**

```java
assertEquals(1, query(filter("method", and(eq("entityId", "17"), eq("resource", "A"))))
        .matchedRecords());
```

Each predicate may match a different projection, but all must occur in the same invocation/hit.

- [ ] **Step 3: Add failing WINDOW and COUNT tests**

WINDOW uses record position around an exact anchor. COUNT sorts by count descending then key ascending and reports `otherCount`.

- [ ] **Step 4: Add failing JDWP CHANGES tests**

```java
assertEquals(0, change.skippedMatchedHits());
assertEquals("28", change.beforeValue());
assertEquals("15", change.afterValue());
```

Add separate tests for sampled gaps, status changes, cross-entity rejection and `CHANGE_SERIES_UNSCOPED`.

- [ ] **Step 5: Run RED query tests**

Run: `mvn -pl case-management -am -Dtest=RegisteredEvidenceQueryTest,CodePathEvidenceQueryTest,JdwpEvidenceQueryTest test`

- [ ] **Step 6: Refactor current monolithic query into one entry and two format-specific readers**

CodePath remains streaming with at most 100000 scanned rows. JDWP remains bounded by the existing 500-hit Summary. No cache or sidecar file is introduced.

- [ ] **Step 7: Implement branch-specific outcomes and continuation**

NO_MATCH is a successful query outcome. Invalid combinations and integrity failures remain Tool failures. Source coverage and query-more flags are calculated independently.

- [ ] **Step 8: Run all case-management tests**

Run: `mvn -pl case-management -am test`

- [ ] **Step 9: Commit Query v2**

```powershell
git add case-management
git commit -m "feat: query dynamic evidence by context and change"
```

### Task 7: 闭合零匹配和部分Evidence链路

**Files:**
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionPostProcessingService.java`
- Modify: `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceSufficiencyEvaluator.java`
- Modify: `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/CollectionApplicationServiceTest.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/JdwpCollectionApplicationServiceTest.java`
- Test: `evidence-engine/src/test/java/org/example/algorithmdebug/evidence/EvidenceSufficiencyEvaluatorTest.java`
- Test: `trace-validator/src/test/java/org/example/algorithmdebug/validator/CollectionEvidenceValidatorTest.java`

**Interfaces:**
- Produces: A citeable `MISSING_EVIDENCE` bundle for a successfully completed but empty/partial Collection, without false Validation coverage.

- [ ] **Step 1: Add regression tests for zero matched CodePath and zero captured JDWP**

```java
assertEquals(EvidenceDecision.INSUFFICIENT, evaluation.decision());
assertFalse(bundle.coveredDimensions().contains(EvidenceDimension.VALIDATION));
assertTrue(result.evidenceId().isPresent());
```

- [ ] **Step 2: Add a failure-boundary test**

Collector/Agent process failure archives a Manifest but does not create target Evidence usable for a diagnosis.

- [ ] **Step 3: Run RED affected module tests**

Run: `mvn -pl ada-core,evidence-engine,trace-validator -am test`

- [ ] **Step 4: Implement only the missing closure behavior**

If existing code already creates the correct insufficient bundle, keep production code unchanged and retain the regression tests. Never grant confirmation merely because a condition produced zero rows.

- [ ] **Step 5: Run affected tests**

Run: `mvn -pl ada-core,evidence-engine,trace-validator -am test`

- [ ] **Step 6: Commit evidence closure**

```powershell
git add ada-core evidence-engine trace-validator
git commit -m "fix: preserve incomplete collection evidence lineage"
```

### Task 8: 接入CLI和OpenCode Tool并定义每个返回分支的模型动作

**Files:**
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/CaseApplicationService.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliFailureMessages.java`
- Modify: `integrations/opencode/tools/algorithm-debug.ts`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCommandExecutorTest.java`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliFailureMessagesTest.java`
- Modify: `integrations/opencode/test/tool-runtime.test.mjs`
- Modify: `integrations/opencode/test/configuration-assets.test.mjs`

**Interfaces:**
- Consumes: one bounded v2 query request through the existing Adapter temporary-request mechanism.
- Produces: ToolResponse 2.0 whose data is EvidenceQueryResult v2 and whose message contains only deterministic recovery guidance.

- [ ] **Step 1: Add failing CLI tests for all modes and structured failures**

Expected codes include `QUERY_MODE_UNSUPPORTED`, `QUERY_DIMENSION_UNKNOWN`,
`QUERY_COMBINATION_INVALID`, `CHANGE_SERIES_UNSCOPED` and existing Artifact integrity failures.

- [ ] **Step 2: Add failing OpenCode Tool-schema tests**

The Tool uses a discriminated `mode`; fields invalid for a mode are rejected before Java invocation. Legacy single-filter calls map to FILTER during the compatibility window.

- [ ] **Step 3: Run RED Java/Node tests**

Run: `mvn -pl algorithm-debug-cli,ada-core -am test`

Run: `node --test integrations/opencode/test/*.test.mjs`

- [ ] **Step 4: Implement request adaptation and bounded error messages**

Temporary request files remain internal, bounded and deleted in `finally`; users do not pass filesystem paths. DFX logs record mode and counts, never returned values.

- [ ] **Step 5: Make JDWP sampling fields explicit in Tool guidance**

Descriptions must explain that `captureFirstMatchedHits=N` captures the first N matched states and `captureEveryMatchedHits=M` samples later states. The model must inspect `skippedMatchedHits` before claiming continuity.

- [ ] **Step 6: Run Java and Node tests**

Run: `mvn -pl algorithm-debug-cli,ada-core -am test`

Run: `node --test integrations/opencode/test/*.test.mjs`

- [ ] **Step 7: Commit Tool integration**

```powershell
git add ada-core algorithm-debug-cli integrations/opencode
git commit -m "feat: expose bounded evidence discovery tools"
```

### Task 9: 更新Skill、Agent、Eval和当前文档

**Files:**
- Modify: `skills/algorithm-debug/SKILL.md`
- Modify: `integrations/opencode/agents/algorithm-debug.md`
- Modify: `docs/current-capabilities.md`
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `method-path-codepathtracer/README.md`
- Modify: `tools/jdwp-collector/README.md`
- Modify: `integrations/opencode/README.md`
- Modify: `agent-evals/suites/quality-50.json`
- Modify: `agent-evals/suites/smoke.json`
- Modify: `agent-evals/grade.mjs`
- Modify: `agent-evals/test/grade.test.mjs`

**Interfaces:**
- Consumes: All completed deterministic capabilities.
- Produces: Model workflow and Eval rules that use those capabilities without target-business semantics.

- [ ] **Step 1: Add failing configuration and Eval grader tests**

Tests require SUMMARY before deep paging, COUNT/FILTER/WINDOW ordering where relevant, NO_MATCH/PARTIAL rejection, sampled-change qualification and complete Evidence lineage.

- [ ] **Step 2: Update Skill to version 3.3**

Encode the ten-step causal loop from the Spec. Preserve the simple-exception branch so easy failures do not trigger unnecessary dynamic runs.

- [ ] **Step 3: Add at least twelve focused Eval scenarios**

```text
codepath-filter-single
codepath-filter-and
codepath-scope-late-match
codepath-scope-no-match-complete
codepath-scope-condition-unavailable
codepath-parent-window
codepath-rare-count
jdwp-consecutive-changes
jdwp-sampled-changes
jdwp-unavailable-value
multiround-codepath-to-jdwp
cross-entity-causal-refinement
```

- [ ] **Step 4: Delete or absorb superseded runtime-evidence descriptions**

After the new design is implemented, update its status to Implemented and remove conflicting old behavior text. Historical ADR decisions remain; current capability documents describe only shipped behavior.

- [ ] **Step 5: Run Node configuration/Eval unit tests**

Run: `node --test integrations/opencode/test/*.test.mjs`

- [ ] **Step 6: Commit workflow and Eval updates**

```powershell
git add skills integrations/opencode docs method-path-codepathtracer tools agent-evals
git commit -m "docs: guide causal dynamic evidence discovery"
```

### Task 10: 全量审计、性能基线和真实端到端验收

**Files:**
- Create: `docs/audits/2026-09-05-dynamic-evidence-discovery-final-audit.md`
- Modify: `docs/designs/2026-09-05-dynamic-evidence-discovery-design.md`

**Interfaces:**
- Consumes: Completed implementation and all generated test reports.
- Produces: One auditable statement of implemented behavior, limits, deviations and real model results.

- [ ] **Step 1: Run affected and root Maven tests**

Run: `mvn -Pcodepath-launcher test`

Expected: all reactor modules PASS on Java 21.

- [ ] **Step 2: Run all OpenCode Adapter tests**

Run: `node --test integrations/opencode/test/*.test.mjs`

Expected: all tests PASS.

- [ ] **Step 3: Build and verify installation without changing system JDK variables**

Run: `powershell -ExecutionPolicy Bypass -File scripts/build-agent.ps1`

Run: `powershell -ExecutionPolicy Bypass -File scripts/verify-opencode-installer.ps1`

- [ ] **Step 4: Establish the 100000-record streaming baseline**

Record fixture size, scanned rows, returned rows, wall time and peak-process memory. Do not create a pass/fail timing threshold from one development machine; compare future index proposals against this baseline.

- [ ] **Step 5: Run targeted real OpenCode E2E scenarios**

Run all twelve scenarios from Task 9. For every started Case, inspect expected/actual Workspace files, Artifact integrity, interaction order and Case DFX log. No empty directory or unexplained missing Artifact is allowed.

- [ ] **Step 6: Run the existing Smoke and Quality suites**

Run the 10-case Smoke suite and the 50-case Quality suite. Record model/provider/version and distinguish Agent failures from provider quota or network failures.

- [ ] **Step 7: Audit causal usefulness, not only Tool success**

For each complex case record: initial dynamic rows, rows after COUNT/FILTER, WINDOW size, JDWP snapshots, CHANGES rows, collection rounds, cited Evidence and whether the final root cause matches the fixture. The feature is not accepted if data volume shrinks but the correct causal records are lost.

- [ ] **Step 8: Audit code and artifact closure**

Confirm every new field has one producer and one consumer; every result branch has a Skill action and Eval; no obsolete Schema, dead class, empty directory, unregistered Artifact or unused response field remains.

- [ ] **Step 9: Write the final audit and mark the design Implemented only after evidence passes**

The audit lists exact commands, reports, failures, known limits and any design deviation. If real E2E is blocked, keep the design status at Review and do not claim completion.

- [ ] **Step 10: Commit final audit**

```powershell
git add docs
git commit -m "docs: audit dynamic evidence discovery"
```

## Plan Self-Review

- Spec coverage: CodePath采集后过滤、Scope采集时过滤、调用关联、JDWP变化、大数据查询、多轮Evidence、Skill和Eval均有独立任务。
- Closure coverage: SUMMARY/FILTER/WINDOW/COUNT/CHANGES、NO_MATCH、PARTIAL、截断、unavailable、完整性失败和采样间隔均定义了模型动作。
- YAGNI check: 没有数据库、索引服务、业务语义引擎、对象图递归、新会话状态机或Collector重写。
- Type consistency: Plan v5、Invocation v2、Evidence Query Result v2在Spec与任务中一致。
- Residual decision: 是否需要持久化稀疏索引只由Task 10性能基线决定，不在本计划预先实现。
