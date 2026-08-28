# Static Analysis and Runtime Evidence Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不引入业务语义和新分析框架的前提下，为 LLM 提供更准确的静态候选关系、重复调用路径差异和指定调用序号状态证据。

**Architecture:** 保持 LLM 规划、Java 编译/采集/校验、Workspace 归档、LLM 解读的现有边界。静态分析输出直接边与多态候选边；CodePath 按可选 Scope 分组；JDWP 分离观察命中和快照采集。

**Tech Stack:** Java 21、Maven、JUnit 5、JDK javac API、CodePathTracer、JDI/JDWP、TypeScript/JavaScript OpenCode Adapter。

**Spec:** `docs/designs/2026-08-28-static-and-runtime-evidence-optimization-design.md`

## Global Constraints

- 不引入新的第三方 Java 依赖或 Maven 模块。
- 不增加算法业务语义、固定采集轮数、SHA 门禁和数字置信度。
- 新 Schema 字段向后兼容，旧产物仍可读取。
- 所有新行为先写失败测试，再实现最小生产代码。
- DFX 只记录结构化阶段摘要和异常栈，不记录敏感业务值。

---

### Task 1: Static analysis classpath and dispatch candidates

**Files:**
- Modify: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/JavaSourceCallGraphAnalyzer.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodCallEdge.java`
- Test: `static-analysis/src/test/java/org/example/algorithmdebug/staticanalysis/JavaSourceCallGraphAnalyzerTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/MethodCatalogJsonTest.java`

**Produces:** Classpath-aware MethodCatalog with `DIRECT` and `POLYMORPHIC_CANDIDATE` edges.

- [ ] Add failing tests for Classpath resolution, fallback, dispatch kinds and English diagnostics.
- [ ] Run affected tests and confirm failures are caused by missing behavior.
- [ ] Pass resolved Maven Test Classpath from CLI to javac.
- [ ] Add bounded concrete override candidate edges using JDK Types/Elements.
- [ ] Add case-scoped DFX summary events.
- [ ] Run contracts, static-analysis and CLI tests.

### Task 2: CodePath scope invocation summary

**Files:**
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanRequest.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanCompiler.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodPathSummary.java`
- Modify: `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/MethodPathNormalizer.java`
- Modify: `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Modify: `schemas/trace/method-path-summary-v2.schema.json`
- Test: matching module tests.

**Consumes:** Method keys from MethodCatalog and immutable CodePath Raw Events.

**Produces:** Optional Scope metadata, invocation ordinals and deterministic in-summary `PATH_n` variants.

- [ ] Add failing Plan, normalization, contract and validation tests.
- [ ] Run affected tests and confirm expected failures.
- [ ] Add optional validated `scopeMethodKey` to CodePath Plan.
- [ ] Pair Scope enter/exit events and derive bounded invocation summaries.
- [ ] Cluster equal ordered call structures without persistent hashes.
- [ ] Validate unpaired/truncated evidence and add DFX counts.
- [ ] Run Plan, normalizer, validator and launcher tests.

### Task 3: JDWP sparse hit capture

**Files:**
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpTracepointRequest.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/JdwpTracepointSpec.java`
- Modify: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpPlanCompiler.java`
- Modify: `tools/jdwp-batch-collector/src/main/java/one/edee/mcp/jdwp/collector/TracePlanExecutor.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/JdwpSnapshotSummary.java`
- Modify: `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/JdwpSnapshotNormalizer.java`
- Modify: matching JDWP Schemas and tests.

**Produces:** Backward-compatible `captureOnHits` and observed/captured/skipped hit evidence.

- [ ] Add failing Plan validation, executor and normalizer tests.
- [ ] Run affected tests and confirm expected failures.
- [ ] Add optional sorted unique `captureOnHits` contract.
- [ ] Count every hit and evaluate snapshots only for selected ordinals.
- [ ] Persist hit ordinals and summary counts; mark missed ordinals PARTIAL.
- [ ] Add bounded DFX summary events.
- [ ] Run JDWP unit, contract and real collector smoke tests.

### Task 4: Structured OpenCode planning tools and workflow

**Files:**
- Modify: `integrations/opencode/tools/algorithm-debug.ts`
- Modify: `integrations/opencode/lib/tool-runtime.mjs`
- Modify: `integrations/opencode/agents/algorithm-debug.md`
- Modify: installed Skill source and adapter tests.
- Modify: `docs/algorithm-debug-workflow-and-artifacts.md`
- Modify: `docs/current-capabilities.md`

**Produces:** Structured plan inputs with adapter-generated plan ID/time and evidence-driven Skill decisions.

- [ ] Add failing adapter contract and Eval expectations.
- [ ] Replace public raw request JSON with structured arguments.
- [ ] Generate protocol metadata and default budgets in the adapter.
- [ ] Remove fixed 5-15 method guidance and document adaptive collection.
- [ ] Update installer-owned copies and usage documentation.
- [ ] Run adapter tests and installer Check/Install verification.

### Task 5: Full verification and artifact audit

- [ ] Run affected module tests after each task.
- [ ] Run root `mvn test` with the configured Agent JDK.
- [ ] Rebuild bundled CodePath and JDWP tools.
- [ ] Reinstall the current repository into OpenCode.
- [ ] Run all deterministic Eval cases.
- [ ] Run real OpenCode success, exception, assertion, Scope and sparse JDWP scenarios.
- [ ] Inspect every E2E Case for Plan, Raw, Derived, Validation, Evidence, manifest and DFX logs.
- [ ] Confirm no empty directories, duplicate payload files, sensitive logs or unsupported completeness claims.
- [ ] Update the design completion record and final capability audit with exact commands and artifact paths.
