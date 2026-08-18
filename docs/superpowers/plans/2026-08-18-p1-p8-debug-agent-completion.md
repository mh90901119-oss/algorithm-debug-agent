# P1～P8 Algorithm Debug Agent Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the static-analysis, CodePath, JDWP, evidence, multi-turn analysis, OpenCode, generic-adapter and evaluation vertical slices so an OpenCode model can debug one Java/Maven algorithm UT from immutable evidence.

**Architecture:** Keep collectors as pinned offline processes behind Java ports. Persist every catalog, plan, manifest, raw trace, derived trace, evidence bundle and report under the existing append-only Case archive; deterministic Java code validates budgets, hashes and provenance while the LLM chooses plans and explains results.

**Tech Stack:** Java 21, Maven, JUnit 5, Jackson 2.17.2, JDK Compiler Tree API, Node.js built-in test runner, OpenCode Custom Tools, external CodePathTracer `f8be120` and JDWP Collector `1ef7d22`.

## Global Constraints

- Follow `docs/designs/2026-08-18-p1-p8-debug-agent-completion-design.md` and repository `AGENTS.md`.
- Do not modify target algorithm source, UT or POM; launch every tool with argv, never a shell command string.
- New persisted DTOs require immutable Java contracts, JSON Schema 1.0 and round-trip/invalid-case tests.
- Every dynamic collection requires a plan, manifest, bounded stdout/stderr, timeout, cleanup and reproduction fingerprint comparison.
- Raw artifacts are write-once; derived files never replace raw files.
- CodePath package-superset capture must be disclosed in the manifest and rejected at preview when estimated cost exceeds the hard budget.
- JDWP listens only on `127.0.0.1`; unsupported projection or sampling fields fail plan compilation.
- Each task follows RED → verify RED → minimal GREEN → refactor → affected tests → audit/fix → commit.
- After each P phase run affected reactor tests, `git diff --check`, stale-placeholder scans and an architecture-boundary audit before continuing.
- Do not push or create a PR unless the user separately requests it.
- The interrupted-work recovery is complete; P1/P2 may be committed only after their full verification gates pass.

---

## P1 — Static Analysis and Collection Plan Foundation

### Task 1: Plan and Source Identity Contracts

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/PlanId.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SourceAnchor.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodCatalogEntry.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodCallEdge.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodCatalog.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/analysis/method-catalog-v1.schema.json`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/MethodCatalogTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/MethodCatalogJsonTest.java`

**Interfaces:**
- Produces: `PlanId(String)`, `SourceAnchor`, `MethodCatalogEntry`, `MethodCallEdge`, `MethodCatalog`.
- `MethodCatalog` includes `caseId/contextId/analysisId/targetTest`, source fingerprint, entries, edges, warnings, completeness and truncation counts.

- [x] **Step 1: Write failing contract tests**

```java
@Test
void rejectsEdgesWhoseMethodsAreMissingFromCatalog() {
    assertThrows(IllegalArgumentException.class, () -> catalog(
            List.of(method("fixture.Target#test()")),
            List.of(edge("fixture.Target#test()", "fixture.Service#missing()"))));
}
```

- [x] **Step 2: Run RED**

Run: `mvn -pl ada-contracts -am -Dtest=MethodCatalogTest,MethodCatalogJsonTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: test compilation fails because the contracts do not exist.

- [x] **Step 3: Implement immutable contracts and Schema**

Use existing `OpaqueIdentifier`, `ContractChecks`, `SchemaVersions`, lower-case SHA-256 and maximums: 50,000 methods, 250,000 edges, 1,000 warnings.

- [x] **Step 4: Run GREEN and contract audit**

Run the Task 1 command, parse every file under `schemas`, and verify `ada-contracts` has no implementation-module dependency.

- [x] **Step 5: Commit**（与 P1/P2 其余变更合并归档于 `6895212`）

Commit message: `feat: define static method catalog contract`.

### Task 2: JDK AST Method Catalog Analyzer

**Files:**
- Modify: `static-analysis/pom.xml`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/StaticAnalysisBudget.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/StaticAnalysisRequest.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/StaticAnalysisException.java`
- Create: `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/JavaSourceCallGraphAnalyzer.java`
- Test: `static-analysis/src/test/java/org/example/algorithmdebug/staticanalysis/JavaSourceCallGraphAnalyzerTest.java`

**Interfaces:**
- Consumes: module root, `TargetTest`, Case/Context/Analysis IDs, source fingerprint and `StaticAnalysisBudget`.
- Produces: `MethodCatalog analyze(StaticAnalysisRequest request)`.
- Dependency: `ada-contracts`; implementation uses public `com.sun.source.*` JDK APIs only.

- [x] **Step 1: Write RED tests for target reachability, overloads and truncation**

```java
@Test
void startsAtTargetMethodAndRecordsResolvedInvocation() {
    MethodCatalog catalog = analyzer.analyze(fixture("TargetTest", "service.solve()"));
    assertTrue(catalog.edges().stream().anyMatch(edge ->
            edge.callerKey().contains("TargetTest#caseUnderTest")
                    && edge.calleeKey().contains("Service#solve")));
}
```

- [x] **Step 2: Verify RED**

Run: `mvn -pl static-analysis -am -Dtest=JavaSourceCallGraphAnalyzerTest -Dsurefire.failIfNoSpecifiedTests=false test`.

- [x] **Step 3: Implement bounded source discovery and AST scanning**

Scan only module `src/main/java` and `src/test/java`, reject symlinks, hash source bytes, use `JavacTask.parse/analyze`, `Trees.getElement` when resolvable and syntax-level warning entries otherwise. Stop deterministically at file/byte/method/edge/deadline budgets.

- [x] **Step 4: GREEN, refactor and audit**

Run module tests. Audit descriptor stability, Windows paths, compiler diagnostics, file handle closure and deterministic sorting.

- [x] **Step 5: Commit**（与 P1/P2 其余变更合并归档于 `6895212`）

Commit message: `feat: build bounded java method catalog`.

### Task 3: CodePath Plan Contract and Compiler

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodSelector.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionBudget.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathCollectionPlan.java`
- Create: `schemas/collection/codepath-plan-v1.schema.json`
- Modify: `debug-plan-engine/pom.xml`
- Create: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanRequest.java`
- Create: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/PlanCompilationException.java`
- Create: `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanCompiler.java`
- Test: `debug-plan-engine/src/test/java/org/example/algorithmdebug/plan/CodePathPlanCompilerTest.java`

**Interfaces:**
- `CodePathCollectionPlan compile(MethodCatalog catalog, CodePathPlanRequest request)`.
- Request contains selected method keys and bounded LLM rationale; compiler derives package prefixes and validates catalog membership.

- [x] **Step 1: RED tests** for unknown methods, more than 200 methods, package-superset estimate above one million events, deterministic plan output and rationale bounds.
- [x] **Step 2: Verify RED** with `mvn -pl debug-plan-engine -am test`.
- [x] **Step 3: Implement contracts, Schema and compiler** with default 50 methods/100k events/16 MiB/5 minutes and hard values from the design.
- [x] **Step 4: GREEN and audit** Schema parity, no LLM calls, stable method/package ordering and explicit `PACKAGE_SUPERSET` scope.
- [x] **Step 5: Commit**（与 P1/P2 其余变更合并归档于 `6895212`）。

### Task 4: Static Catalog and Plan Archiving/CLI

**Files:**
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommand.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Test: corresponding repository/core/CLI tests.

**Interfaces:**
- CLI: `static analyze --workspace ... --project-id ... --case-id ... --analysis-id ...`.
- CLI: `plan codepath create ... --request-file <json>`.
- Archive: `analyses/<analysisId>/method-catalog.json` and `plans/<planId>.json`, create-new only.

- [x] **Step 1: RED repository/core/CLI tests** proving identity validation, write-once paths and ToolResponse Artifact references.
- [x] **Step 2: Verify RED** across `case-management,ada-core,algorithm-debug-cli`.
- [x] **Step 3: Implement minimal orchestration and commands**; request files are UTF-8 regular non-symlink files under 64 KiB.
- [x] **Step 4: GREEN and P1 audit**: run affected reactor, `mvn test`, Schema parse, `git diff --check`, public Javadoc and stale empty-module scan.
- [x] **Step 5: Fix audit findings with regression tests, rerun P1 gates, commit**（与 P1/P2 其余变更合并归档于 `6895212`）。

### P1 Audit Remediation: Bounded Analysis and Large Catalog Compatibility

**Files:** P1 contracts/Schema, `JavaSourceCallGraphAnalyzer`, `CodePathPlanCompiler`,
`BoundedDocumentMapper`, `AtomicDocumentWriter`, `CaseArchiveRepository`,
`StaticAnalysisApplicationService` and focused tests.

**Interfaces:**
- `timeoutMillis` is a cooperative deadline over bounded compiler input; it is not a hard wall-clock timeout.
- `MethodCatalog` publishes bounded exact package census plus census completeness.
- Full MethodCatalog JSON uses the bounded streaming Artifact channel; ordinary control documents remain capped at 1 MiB.

- [x] **Step 1: RED analyzer tests** proving source discovery does not retain more than the file budget, source reads do not exceed the byte budget, method/edge visitors stop at their budgets, compiler `ERROR` makes the result incomplete, and critical truncation reasons survive 1,000 parse warnings.
- [x] **Step 2: GREEN analyzer** with bounded source objects, a shared cooperative deadline guard and immediate visitor stop; document that `JavacTask.parse/analyze` is checked before/after and requires a future worker process for a hard timeout.
- [x] **Step 3: RED/GREEN contract tests** for real JVM method descriptors, Schema-equivalent descriptor patterns, bounded exact package census and incomplete-census invariants.
- [x] **Step 4: RED/GREEN plan tests** rejecting incomplete census and cross-exact-package selection, using only the selected exact package census for cost.
- [x] **Step 5: RED/GREEN repository tests** for a MethodCatalog JSON larger than 1 MiB written/read through temp + fsync + atomic create-new without a whole-document byte array, plus selector/anchor identity and duplicate rejection.
- [x] **Step 6: RED/GREEN core/CLI tests** mapping rationale, archive, identity and artifact failures to `PLAN_*`/`STATIC_*` codes rather than `CASE_*` or `INTERNAL_ERROR`.
- [x] **Step 7: Verification** run focused tests, affected reactor, Schema parse, `git diff --check`, dependency-boundary and stale-placeholder scans; do not commit in this recovery session.

### P1 Second Audit Remediation: Package Tree and Catalog Byte Closure

**Files:** `ada-contracts` package-scope contract and CodePath Plan Schema,
`StaticAnalysisBudget`, a focused Catalog JSON upper-bound ledger,
`JavaSourceCallGraphAnalyzer`, `CodePathPlanCompiler`, P1 design text and focused tests.

**Interfaces:**
- A selected package contains only itself and packages beginning with `selectedPackage + "."`; `com.foo` never contains `com.foobar`.
- `StaticAnalysisBudget.maxCatalogBytes` is 16–128 MiB with a 64 MiB default.
- A returned MethodCatalog has a conservative JSON upper bound no greater than `maxCatalogBytes`; the archive writer independently enforces 128 MiB.
- Static-analysis `timeoutMillis` remains cooperative; a worker process is an explicit future limitation, not completed P1 work.

- [x] **Step 1: RED package-tree tests** with `com.foo`, `com.foo.sub` and `com.foobar`, proving Plan census cost and boundary exclusion use one predicate.
- [x] **Step 2: GREEN package-tree contract/compiler** while retaining the current single exact-package selector restriction; document mandatory P2 Launcher reuse.
- [x] **Step 3: RED budget and analyzer tests** for the 16/64/128 MiB bounds and a long-key/long-path, many-edge fixture whose real Jackson UTF-8 bytes remain within its budget after catalog-byte truncation.
- [x] **Step 4: GREEN Catalog upper-bound ledger** using `2 + 6 * UTF-16 length`, full top-level dynamic fields, worst-case 1,000 × 2,048 warning reservation, per-method entry+census and per-edge charges; stop on the first over-budget candidate and mark incomplete.
- [x] **Step 5: RED/GREEN Schema parity tests** recursively applying minLength/maxLength/pattern plus constructor conditions to valid and invalid TargetTest/selector instances without adding a Schema-validator dependency.
- [x] **Step 6: Documentation and verification** remove any positive P1 hard wall-clock claim, run focused tests and affected reactor, parse all Schemas, run `git diff --check`, and do not commit.

Step 6 verification note: `.m2` read access was granted on 2026-08-18. Focused tests, the combined downstream
reactor, all Schema parsing, dependency-boundary scan and `git diff --check` are GREEN.

---

## P2 — CodePathTracer Integration

### Task 5: Method Path SPI and Manifest

**Files:** `method-path-spi/pom.xml`; new `MethodPathCollector`, `MethodPathCollectionRequest`, `MethodPathCollectionResult`, `MethodPathManifest`, `CollectionCompletion`; Schema `schemas/collection/method-path-manifest-v1.schema.json`; contract tests.

**Interfaces:** `MethodPathCollectionResult collect(MethodPathCollectionRequest request)` throws structured `MethodPathCollectionException`. Result paths must be inside the supplied collection directory.

- [x] Write RED tests for success/truncation/tool failure, plan hash, stage/processStarted, AgentFailure,
  raw/filtered digest+bytes, logs, capture/evidence scope, match precision and path escape.
- [x] Run `mvn -pl method-path-spi -am test` and confirm missing types.
- [x] Implement immutable SPI DTOs and Schema with tool/run/plan/provenance metrics.
- [x] Run GREEN and Schema parity audit（与 P1/P2 其余变更合并归档于 `6895212`）。

### Task 6: Streaming Method Path JSONL Filter

**Files:** `method-path-codepathtracer/pom.xml`; new `MethodPathEvent`, `MethodPathJsonlFilter`, `MethodPathFilterResult`, `CodePathAdapterException`; tests and small JSONL fixtures.

**Interfaces:** `MethodPathFilterResult filter(Path raw, Path filtered, CodePathCollectionPlan plan)`; write via temp + atomic move, never load complete trace.

- [x] RED tests: allowlist, descriptor exact/missing degradation, enter/exit, multiple threads, invalid line,
  giant no-newline input, max events, max bytes, depth and deterministic truncation.
- [x] Verify RED with focused Maven test.
- [x] Implement Jackson streaming line parsing, SHA-256, counters and bounded error details.
- [x] GREEN, generated 1,000,000-event performance test and memory/bytes audit（与 P1/P2 其余变更合并归档于 `6895212`）。

### Task 7: External CodePath Process Collector

**Files:** Agent-owned `tools/code-path-tracer-junit-launcher`; new `CodePathToolConfiguration`,
`CodePathCommandFactory`, `CodePathProcessCollector`; modify parent profile `codepath-launcher`,
`config/toolchain-lock.json`, `config/collection-limits.yaml`, Doctor checks; fake process tests.

**Interfaces:** command uses configured Java executable, pinned external fat JAR, target test classpath, `--test`, derived package include and raw trace path. No shell.

- [x] RED Launcher tests for exact/child package boundary, complete UTF-8 lines, concurrent callbacks,
  byte/event limits and “truncated but UT continues”.
- [x] Implement a streaming `TraceJsonlSink` with runtime `maxOutputBytes<=50 MiB` and
  `maxEvents<=1,000,000`; no complete trace list in memory; print one structured Launcher Summary.
- [x] RED parent tests for exact argv budgets, missing/incorrect JAR SHA, timeout, start failure,
  arbitrary nonzero exit, structured target failure, oversized Raw breach and manifest facts.
- [x] Implement using existing process supervision abstractions extracted from `debug-harness` only if needed through a small shared port; do not duplicate process-tree cleanup.
- [x] Add real CodePath Bundle conditional Smoke against Wafer Demo and verify configured/pinned Bundle SHA.
- [x] Audit license/NOTICE, package-superset disclosure, unbounded external launcher risk and refusal preview（与 P1/P2 其余变更合并归档于 `6895212`）。

### Task 8: CodePath Collection Application Flow

**Files:** extend Case archive collection layout/repository; create `CollectionId`; add `CollectionApplicationService`; CLI `collection codepath execute`; integration tests.

**Interfaces:** archive request/plan/manifest/raw/filtered/log artifacts under one Collection and attach `caseId/contextId/analysisId/runId/planId/collectionId`.

- [x] RED integration fixture: injected SPI, plan required, request archived before failure, source drift before
  (Collector zero calls), source drift after (Raw retained/evidence unusable), first successful collection,
  target assertion failure, tool missing and baseline mismatch.
- [x] Implement use case without automatic retry; Core depends only on SPI/TargetClasspathResolver;
  normalize target and Agent failures independently; Core atomically archives every terminal Manifest.
- [x] Return bounded ToolResponse summary with standard case-relative `ArtifactReference` entries; make
  configured Java/Launcher/hash drive runtime and Doctor checks, without absolute development paths.
- [x] P2 audit and repair: affected tests, real Smoke, root `mvn test`, path/provenance/cleanup scan.
- [x] Commit（与 P1/P2 其余变更合并归档于 `6895212`）。

**P2 final audit remediation (2026-08-18):**

- [x] Reject Collector results whose `caseId/contextId/analysisId/runId/planId/collectionId` differ from the request.
- [x] Derive summary paths from actual Case-relative `ArtifactReference` values and expose collection request/Gantt artifacts.
- [x] Cover success, `TARGET_FAILED` with Gantt, post-collection source drift and changed-Gantt Baseline gates at application level.
- [x] Cover real process start failure and timeout; accept platform-specific nonzero termination codes without confusing them with success.
- [x] Run `mvn -Pcodepath-launcher clean test`, rebuild/verify locked Launcher SHA, parse all Schemas and run a non-skipped real target smoke.

---

## P3 — JDWP Integration

详细执行计划：`docs/superpowers/plans/2026-08-18-p3-jdwp-integration.md`。P3 先以保守预算接入当前已验证
Collector MVP；变量 allowlist、字段投影、采样和 Collector 内部 Raw 字节硬限制属于后续 P0，不得在本阶段伪装支持。

### Task 9: JDWP Plan and Manifest Contracts

**Files:** contracts `TracepointSpec`, `CaptureSpec`, `JdwpCollectionPlan`, `JdwpManifest`; Schemas `jdwp-plan-v1`, `jdwp-manifest-v1`; tests.

- [x] RED tests for loopback-only target, 20 tracepoints, method/source hash identity, all-or-nothing locals, supported limits and strict unsupported-capability rejection.
- [x] Implement immutable contracts matching the locked Collector's supported fields.
- [x] Contract/Schema audit and commit `8a46f93 feat: define bounded jdwp collection contracts`.

### Task 10: Source Anchor to Collector Plan Compiler

**Files:** `debug-plan-engine/JdwpPlanRequest`, `JdwpPlanCompiler`, `CollectorDebugPlanWriter`; tests.

- [x] RED tests for changed source Hash, missing line, unknown method, duplicate point, unsupported field projection and deterministic Collector JSON.
- [x] Implement compiler using current MethodCatalog and streaming source file re-hash.
- [x] Run GREEN and validate generated JSON against the locked external `DebugPlan.validate()`; commit recorded with Task 2.

### Task 11: JDWP Target and Collector Coordination

**Files:** `jdwp-collector-adapter/pom.xml`; new `LoopbackPortAllocator`, `JdwpLaunchSpec`, `JdwpTargetCommandFactory`, `JdwpCollectorCommandFactory`, `JdwpCollectionCoordinator`; tests.

- [x] RED tests for loopback allocation, argv, attach ordering, collector failure before/after resume, timeout, process survivors and bounded logs.
- [x] Implement application-assigned loopback port, injected process starters/supervisor and explicit lifecycle stages.
- [x] Add conditional real Collector Smoke using the pinned JAR; minimal tracepoint hit/resume is verified. Wafer one-point Smoke remains in the P3 release audit.
- [x] Audit resume safety, localhost binding, JAR hash, Raw byte cutoff and target/Collector fact preservation; commit `ce6b30c`.

### Task 12: JDWP Collection Application Flow

**Files:** extend collection repository/Core/CLI with `collection jdwp execute`; integration tests.

- [x] RED Core/CLI fixture for successful trace, target assertion/business failure, attach failure, source drift and baseline mismatch.
- [x] Implement append-only Plan/Collection archive and bounded Artifact references using the common collection layout.
- [ ] P3 audit, regression fixes, affected/root tests and real Smoke.
- [ ] Commit `feat: execute archived jdwp collections`.

---

## P4 — Normalize, Validate and Build Evidence

### Task 13: Method Path and JDWP Normalizers

**Files:** normalizer contracts/Schemas; `MethodPathNormalizer`, `JdwpSnapshotNormalizer`; tests/fixtures.

- [ ] RED tests for balanced/unbalanced calls, threads, truncation, hit aggregation, variable projection, invalid JSON and output budgets.
- [ ] Implement streaming raw-to-derived conversion with source Artifact SHA provenance.
- [ ] Audit no Collector DTO leakage and no Raw rewrite; commit `feat: normalize runtime traces`.

### Task 14: Collection Evidence Validator

**Files:** `ValidationStatus`, `ValidationFinding`, `CollectionValidation`; Schema; `CollectionEvidenceValidator`; tests.

- [ ] RED tests for valid, Schema invalid, hash mismatch, truncated, missing reference, same-context changed, cross-context changed and contradictory artifacts.
- [ ] Implement deterministic validation; never call LLM.
- [ ] Audit all confirmation-blocking cases and commit `feat: validate collection evidence`.

### Task 15: Evidence Bundle and Sufficiency

**Files:** contracts `EvidenceBundle`, `EvidenceClaim`, `EvidenceCoverage`, `SufficiencyEvaluation`; Schemas; `EvidenceBundleBuilder`, `EvidenceSufficiencyEvaluator`; tests.

- [ ] RED tests for input/source/runtime/result coverage, provenance refs, claim-level restrictions, sufficient/insufficient/contradicted outcomes.
- [ ] Implement only deterministic coverage rules; `LLM_HYPOTHESIS` can never satisfy a required dimension.
- [ ] Audit bounded summaries and commit `feat: build and evaluate evidence bundles`.

### Task 16: Evidence Pipeline Application Flow

**Files:** Case evidence layout/repository, Core `EvidenceApplicationService`, CLI `evidence build/inspect`, integration tests.

- [ ] RED tests for write-once evidence, collection identity, derived artifacts, Digest reference and tool failure.
- [ ] Implement pipeline and Artifact archiving.
- [ ] P4 audit, repair, reactor/root tests and commit `feat: archive validated evidence`.

---

## P5 — Complete Multi-turn Analysis Records

### Task 17: Analysis Completion Contract and Repository

**Files:** contracts `AnalysisCompletion`, `AnalysisClaim`, `ReportId`; Schema; Case layout/repository; tests.

- [ ] RED tests for completion identity, cited Evidence existence/hash, claim types, answer Artifact and overwrite rejection.
- [ ] Implement create-new completion and bounded reader.
- [ ] Audit historical Analysis compatibility and commit `feat: persist analysis completions`.

### Task 18: Case Digest v2 Compatibility

**Files:** additive `CaseDigestV2` or versioned compatible extension, Schema, reader tests and migration fixtures.

- [ ] RED tests reading old Case, recent completions, reused evidence, open gaps and 1 MiB truncation.
- [ ] Implement without rewriting v1 documents; CLI negotiates newest supported digest.
- [ ] Audit version compatibility and commit `feat: expose completed analyses in case digest`.

### Task 19: Analysis and Artifact CLI

**Files:** Core `AnalysisApplicationService`, `BoundedArtifactReader`; CLI `analysis begin`, `analysis complete`, `artifact read`; tests.

- [ ] RED tests matching OpenCode Tool arguments, stdin/question/answer files, path escape, byte/line ranges and ToolResponse 2.0.
- [ ] Implement commands and wire existing `case open` behavior under `analysis begin` without duplicating logic.
- [ ] P5 audit, integration multi-turn scenario, root tests and commit `feat: complete multi-turn analysis cli`.

---

## P6 — OpenCode Daily-use Integration

### Task 20: Lock and Verify Local OpenCode Contract

**Files:** update ADR-007/design/toolchain lock; create an environment verification record under `docs/experiments`; no production behavior yet.

- [ ] Inspect installed `opencode --version/help` and official pinned documentation; record agent/command/tool/config discovery and license.
- [ ] Build a temporary-config proof that loads one no-op external tool without modifying user configuration.
- [ ] Audit the proof and freeze the supported version range.

### Task 21: Idempotent Installer and Project Map

**Files:** `integrations/opencode/install.mjs`, `lib/project-map.mjs`, tests and fixture configs.

- [ ] RED Node tests for install/check/upgrade/uninstall, exact ownership markers, atomic backup, crash rollback, path spaces and ambiguous projects.
- [ ] Implement thin loaders that reference repository assets; never copy canonical Skill text.
- [ ] Run Node tests and audit user-config preservation/security.

### Task 22: Real Tool Mapping and End-to-end Debug Case

**Files:** update Tool TS, Agent, Command, Skill and E2E scripts.

- [ ] RED contract tests for `analysis_begin/run_test/static_analyze/plan_create/collection_execute/evidence_build/artifact_read/analysis_complete`.
- [ ] Map to implemented CLI with injected workspace/project ID and bounded temp files.
- [ ] Run pinned OpenCode E2E in a temporary target project, including failed UT and multi-turn reuse.
- [ ] P6 audit, rollback test, docs/root regression and commit `feat: install opencode debug agent`.

---

## P7 — Generic Maven/JSON Algorithm Adapter

### Task 23: Declarative Adapter Contract

**Files:** Adapter definition/JSON snapshot contracts, Schema, SDK tests.

- [ ] RED tests for Maven-only build, relative POM/input/output paths, safe properties, result glob, root JSON type and required JSON Pointers; reject absolute paths, `..`, shell tokens and unbounded glob.
- [ ] Implement immutable validated definition and Schema.
- [ ] Audit SPI dependency direction and commit `feat: define declarative json adapter`.

### Task 24: Declarative Adapter Runtime

**Files:** SDK/runtime classes or a focused `adapters/declarative-json-adapter` module, ServiceLoader entry, tests.

- [ ] RED tests for inspect, launch argv, input discovery, dynamic timestamp result discovery and bounded JSON validation.
- [ ] Implement generic parser without business-field interpretation.
- [ ] Audit path/symlink/command safety and commit `feat: run declarative json algorithms`.

### Task 25: Registration and Real Generic Fixture

**Files:** Project registration contract v2 compatibility, CLI `--adapter-config`, Core catalog, integration fixture/docs.

- [ ] RED tests for immutable config Artifact, registration reuse/change, unsupported config and independent Maven module success/failure.
- [ ] Implement selection and packaged ServiceLoader support.
- [ ] P7 audit, root/fixture tests and commit `feat: register generic algorithm adapters`.

---

## P8 — Knowledge, Reporting, Evaluation and Release Gates

### Task 26: Versioned Knowledge Catalog

**Files:** knowledge contracts/Schema, `KnowledgeCatalog`, repository sample entries and tests.

- [ ] RED tests for source URI/reference, applicability, invalidation condition, version, target adapter and bounded retrieval.
- [ ] Implement deterministic filtering; knowledge is context, never confirmed runtime fact.
- [ ] Audit provenance and commit `feat: add versioned algorithm knowledge`.

### Task 27: Evidence Reporter

**Files:** report contracts/Schema, `EvidenceReportBuilder`, Markdown renderer and tests.

- [ ] RED tests for claim grades, citations, missing Artifact, contradictory Evidence, INCONCLUSIVE output and no sensitive absolute paths.
- [ ] Implement JSON source report plus derived Markdown.
- [ ] Audit citation hashes and commit `feat: render evidence backed reports`.

### Task 28: Offline Agent Evaluation

**Files:** eval contracts/Schema, `EvalRunner`, Golden fixtures for six scheduling questions, tests.

- [ ] RED tests for sufficient, insufficient, tool failure and wrong-hypothesis rejection; bind code/Skill/tool/Schema versions.
- [ ] Implement deterministic fixture evaluator and optional external model-runner port.
- [ ] Audit that offline results are not labeled model quality; commit `feat: run reproducible agent evaluations`.

### Task 29: Security, Performance and Release Gates

**Files:** redactor, generated performance tests, Maven Enforcer/dependency convergence, CycloneDX/license configuration, CI/offline scripts, docs.

- [ ] RED tests for credential/path redaction, large trace budgets and dependency boundary violations.
- [ ] Implement gates with pinned plugin versions and documented Apache-2.0/MIT notices.
- [ ] Run million-event CodePath, 100k-event JDWP, 1k-Evidence and full E2E; record actual time/heap/bytes.
- [ ] Final P8 and whole-architecture audit; fix every finding with regression tests.
- [ ] Run `mvn test`, all Node tests, strict Schema parsing, real Wafer Baseline/CodePath/JDWP Smoke, `git diff --check` and repository status.
- [ ] Update every design completion record, architecture status, README, Skill, CLI docs and development plan.
- [ ] Commit `feat: complete algorithm debug agent evidence workflow`.

## Plan Self-review

- Spec coverage: Tasks 1–4=P1, 5–8=P2, 9–12=P3, 13–16=P4, 17–19=P5, 20–22=P6, 23–25=P7, 26–29=P8.
- Placeholder scan: no unresolved placeholders or undefined shorthand implementation steps; every task names exact behavior, test command or affected module.
- Type consistency: plans use `PlanId`; collections use `CollectionId`; Evidence and reports remain separate immutable identities; every persisted type has a Schema task.
- Dependency consistency: contracts/SPI point inward; Collector implementations depend on SPI; Core orchestrates; CLI/OpenCode remain outer layers.
- Safety consistency: no target-source mutation, shell execution, remote JDWP, Raw overwrite or LLM-based deterministic validation.
