# Context and CodePath Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan. Also use `superpowers:test-driven-development` for each behavior change, `superpowers:systematic-debugging` for unexpected failures, and `superpowers:verification-before-completion` before completion or commit claims.

**Goal:** Replace automatic workspace-fingerprint Context splitting with an explicit minimal Context, and make CodePath collect only the exact methods selected in an archived plan for the current single-thread target UT.

**Architecture:** `case-management` appends a minimal `ContextRecord` only for a new Case or explicit `CREATE_NEW`; runtime changes remain model-visible Run comparison facts and never mutate Context implicitly. Static analysis produces an exact method catalog without module source or package-census fingerprints. The launcher reads an archived CodePath Plan v2, matches `className + methodName + descriptor` before writing, enforces one selected-method thread and hard budgets, and passes a single raw stream to normalization and validation. JDWP retains tracepoint-level `SourceAnchor` checks while dropping the old whole-module snapshot dependency.

**Tech Stack:** Java 21, Maven reactor, JUnit 5, Jackson 2.17.2, JDK NIO, external CodePathTracer `f8be120`, JUnit Platform Launcher.

**Spec:** `docs/designs/2026-08-18-context-codepath-simplification-design.md`

## Global Constraints

- Scope is the user-approved normal path: one Maven module with its own `pom.xml`, one single-thread JUnit 5 target method, repeated analyses and collections in one Case.
- Do not modify the target algorithm source, UT, input or POM. The Skill must instruct the user/model to create a new Context explicitly after an intentional target change.
- Do not calculate or compare repository, module-source, UT-source, input, POM or Git fingerprints for Context or CodePath.
- Do not create package-superset CodePath plans, collect a package and post-filter it, or retain compatibility fields for that flow.
- Do not modify or fork upstream CodePathTracer in this phase. Report the remaining global Advice cost honestly and measure it before proposing an upstream matcher.
- Preserve tracepoint-level `SourceAnchor` in JDWP. Only the whole-module `sourceFingerprintSha256` and repeated source scans are removed.
- Keep `maxEvents`, `maxBytes` and `timeoutMillis`; remove `maxCallDepth`. A second thread that hits a selected method is a structured unsupported error.
- Preserve append-only `caseId/contextId/analysisId/runId/planId/collectionId/evidenceId` storage, target/tool failure separation and same-Context uninstrumented baseline comparison.
- This is a development-stage clean break: write/read only v2 for changed contracts, delete v1 schemas and branches, and recreate local development workspaces. Do not add migration or fallback readers.
- Every behavior task is RED → GREEN → REFACTOR. Never weaken an assertion or delete a useful failure case merely to make the build pass.
- Each implementation task ends with affected-module tests, a focused code audit and bug fixes with regression tests. Because the Context v2 break spans Tasks 2–5 and the CodePath v2 break spans Tasks 6–9, do not create an intermediate commit that leaves the Maven reactor uncompilable: commit the Context cluster after Task 5 and the CodePath cluster after Task 9.

## Task 1: Freeze the architecture decision and breaking-contract inventory

**Files:**

- Create `docs/decisions/ADR-010-explicit-context-and-exact-codepath.md`
- Modify `docs/decisions/ADR-006-case-as-analysis-dossier.md`
- Modify `docs/decisions/ADR-009-generic-runtime-evidence-before-domain-mapping.md`
- Modify `docs/architecture/README.md`
- Modify `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify `docs/architecture/algorithm-debug-agent-complete-design.md`
- Modify `docs/designs/2026-08-12-case-context-run-outcome-multiturn-analysis-design.md`
- Modify `docs/designs/2026-08-18-p3-jdwp-integration-design.md`
- Modify `docs/designs/2026-08-18-p4-generic-runtime-evidence-design.md`
- Modify `docs/designs/2026-08-18-p1-p8-debug-agent-completion-design.md`

**Decision text to freeze:**

```text
Context = explicit analysis-version identity, not a workspace snapshot.
New Case -> create initial Context.
Existing Case + REUSE_LATEST -> reuse latest Context.
Existing Case + CREATE_NEW -> append a new Context.
Run CHANGED -> model-visible fact only; never an implicit Context transition.
CodePath = exact plan selectors at event emission, single selected-method thread.
JDWP = no module fingerprint, but exact tracepoint SourceAnchor remains mandatory.
```

**Steps:**

1. Update ADR-006 so its automatic fingerprint split is explicitly superseded by ADR-010; do not silently rewrite the historical decision.
2. Add ADR-010 with context, decision, rejected alternatives, consequences, clean-break compatibility and rollback via Git.
3. Update ADR-009 to distinguish the common CodePath method identity from JDWP line-level `SourceAnchor`.
4. Replace architecture diagrams that show Context snapshot scanning or package-superset/post-filter CodePath with Mermaid diagrams matching the approved spec.
5. Mark older design sections as superseded where their source fingerprint or package plan fields no longer apply; link to the new design instead of leaving contradictory active requirements.
6. Verify: `rg -n "自动.*Context|PACKAGE_SUPERSET|MethodPathJsonlFilter|sourceFingerprintSha256" docs/architecture docs/decisions docs/designs` returns only historical/superseded explanations and the simplification rationale.
7. Audit: every changed diagram has prose; JDWP `SourceAnchor` is not accidentally removed; no future upstream CodePath change is presented as implemented.
8. Commit: `docs: approve explicit context and exact codepath architecture`.

## Task 2: Define minimal Context v2 and remove snapshot-only contracts

**Files:**

- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ContextRecord.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseManifest.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseOpenResult.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ContextSnapshot.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SourceSnapshot.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InputSnapshot.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InputSnapshotStatus.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BuildSnapshot.java`
- Replace `schemas/case/context-snapshot-v1.schema.json` with `schemas/case/context-record-v2.schema.json`
- Replace `schemas/case/case-manifest-v1.schema.json` with `schemas/case/case-manifest-v2.schema.json`
- Modify `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveContractsTest.java`
- Modify `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveJsonTest.java`

**Public contracts:**

```java
public record ContextRecord(
        String schemaVersion,
        CaseId caseId,
        ContextId contextId,
        Instant createdAt) {}

public record CaseManifest(
        String schemaVersion,
        CaseId caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String adapterId,
        String initialQuestion,
        Instant createdAt) {}

public record CaseOpenResult(
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        boolean caseCreated,
        boolean contextCreated,
        CaseDigest digest) {}
```

**TDD steps:**

1. RED: replace snapshot construction tests with tests proving `ContextRecord` has exactly four JSON fields, validates v2 and identity, and has no project/source/input/build/fingerprint/warning fields.
2. RED: add `CaseManifest` tests requiring a bounded nonblank `adapterId`, rejecting v1 and unknown JSON fields.
3. RED: update `CaseOpenResult` tests to use `contextCreated`; assert it does not claim source change detection.
4. RED: add Schema equivalence tests for `context-record-v2` and `case-manifest-v2`, including rejection of former v1 examples.
5. Run `mvn -pl ada-contracts -am test`; expected RED is compilation/contract failure because v2 records and constants do not exist.
6. GREEN: implement the three record changes with Chinese Javadoc and exact bounds already used for adapter IDs; update version constants to `2.0`.
7. GREEN: delete the five snapshot-only contract types after `rg` confirms all production consumers are assigned to later tasks; during this task, fix compilation only in contract tests, not by adding compatibility stubs.
8. REFACTOR: keep `SnapshotCompleteness` because the bounded static method catalog still uses it; delete only types with no post-plan responsibility.
9. Verify: `mvn -pl ada-contracts -am test`.
10. Audit: `rg -n "repositoryRevision|sourceSnapshot|inputSnapshot|buildSnapshot|fingerprintSha256" ada-contracts/src/main schemas/case` returns no Context fields; all v2 schemas have `additionalProperties: false`.
11. Checkpoint: do not commit yet; Tasks 2–5 are one cross-module Context v2 migration.

## Task 3: Implement explicit Context selection and append-only persistence

**Files:**

- Create `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextMode.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseSessionRequest.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseSessionService.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseDigestReader.java`
- Delete `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextSnapshotBuilder.java`
- Delete `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextSnapshotRequest.java`
- Delete `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextInputProbe.java`
- Modify `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseSessionServiceTest.java`
- Modify `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`
- Modify `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseDigestReaderTest.java`
- Delete `case-management/src/test/java/org/example/algorithmdebug/casecore/ContextSnapshotBuilderTest.java`

**Public behavior:**

```java
public enum ContextMode {
    REUSE_LATEST,
    CREATE_NEW
}

public record CaseSessionRequest(
        Optional<CaseId> caseId,
        ProjectId projectId,
        TargetTest targetTest,
        String adapterId,
        String question,
        ContextMode contextMode) {}
```

**TDD steps:**

1. RED: add tests for new Case + either mode creating exactly one initial Context and one Analysis.
2. RED: add tests for existing Case + default `REUSE_LATEST` creating only an Analysis and returning `contextCreated=false`.
3. RED: add tests for existing Case + `CREATE_NEW` appending a Context and Analysis without overwriting the previous files.
4. RED: add corruption test: existing Case with no readable Context + `REUSE_LATEST` returns `CONTEXT_NOT_FOUND`; it must not silently repair history.
5. RED: add repository ordering tests using `createdAt` then `contextId`, and confirm reproduction lookup still searches prior explicit Contexts.
6. Run `mvn -pl case-management -am test`; expected RED is missing `ContextMode`/`ContextRecord` behavior.
7. GREEN: make `CaseSessionService` choose a Context solely from `ContextMode`; remove filesystem scans and fingerprint equality branches.
8. GREEN: update repository/digest serialization and ordering from `ContextSnapshot` to `ContextRecord` while preserving identity and append-only checks.
9. GREEN: delete builders/probes and their test after no production references remain.
10. REFACTOR: keep Context creation in one private method that consumes `Clock` and `OpaqueIdGenerator`; do not introduce a policy engine.
11. Verify: `mvn -pl case-management -am test`.
12. Audit: use a counting fake filesystem/service to prove repeated `REUSE_LATEST` performs no target source/input/POM traversal.
13. Checkpoint: do not commit yet; retain the RED/GREEN evidence for the final Context cluster audit.

## Task 4: Expose Context mode through core, CLI and the OpenCode integration

**Files:**

- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/CaseApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify `ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java`
- Modify `ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommand.java`
- Modify `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Modify `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliArgumentsTest.java`
- Modify `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCommandExecutorTest.java`
- Modify `integrations/opencode/commands/debug-case.md`
- Modify `skills/algorithm-debug/SKILL.md`
- Modify `integrations/opencode/README.md`
- Modify `algorithm-debug-cli/README.md`

**CLI contract:**

```text
case open ... [--context-mode reuse|new]
default: reuse
reuse + new Case: create initial Context
new + existing Case: append Context before the new Analysis
```

**TDD steps:**

1. RED: parser tests accept omitted/reuse/new, normalize only those exact values, reject duplicates and unknown values with `CONTEXT_MODE_INVALID`.
2. RED: executor tests prove the selected mode reaches `CaseApplicationService` and response JSON uses `contextCreated`.
3. RED: core tests prove adapter selection comes from immutable `CaseManifest.adapterId`, not Context; target test comes from the same manifest.
4. RED: add a regression test proving a `RunComparisonStatus.CHANGED` response does not append a Context.
5. Run `mvn -pl ada-core,algorithm-debug-cli -am test`; expected RED is API/JSON mismatch.
6. GREEN: remove snapshot/input discovery and Java-version arguments from Case opening; pass `ContextMode` and registered adapter ID explicitly.
7. GREEN: update Run service and service wiring to read minimal Context only for identity and Case manifest for adapter/target test.
8. GREEN: update the thin OpenCode command and Skill: reuse history by default; use `--context-mode new` only after the user/model explicitly decides target code/UT/input changed; never infer it from Gantt change.
9. REFACTOR: keep the adapter thin—no Context decision rules in shell/Markdown wrappers.
10. Verify: `mvn -pl ada-core,algorithm-debug-cli -am test` and manually inspect `case open --help` output.
11. Audit: command docs match parser spelling; no documented wrapper launch requirement is reintroduced.
12. Checkpoint: do not commit yet; Task 5 removes the remaining old Context consumers before the cluster commit.

## Task 5: Decouple static analysis, JDWP and evidence from whole-module Context snapshots

**Files:**

- Delete `ada-core/src/main/java/org/example/algorithmdebug/core/SourceSnapshotReader.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/StaticAnalysisApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/JdwpCollectionApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/StaticAnalysisRequest.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/JdwpCollectionPlan.java`
- Modify `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpPlanRequest.java`
- Modify `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/JdwpPlanCompiler.java`
- Replace `schemas/collection/jdwp-plan-v1.schema.json` with `schemas/collection/jdwp-plan-v2.schema.json`
- Modify `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceBuildSources.java`
- Modify `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceBundleBuilder.java`
- Modify `trace-validator/src/main/java/org/example/algorithmdebug/validator/JdwpValidationInput.java`
- Modify affected tests in `ada-core`, `ada-contracts`, `debug-plan-engine`, `evidence-engine` and `trace-validator`

**Required boundary:**

```java
public record JdwpCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<JdwpTracepointSpec> tracepoints,
        JdwpCollectionBudget budget,
        String rationale,
        Instant createdAt) {}
// JdwpTracepointSpec.sourceAnchor remains required.
```

**TDD steps:**

1. RED: static-analysis service test verifies analysis reads the current registered `moduleRoot` once and performs no before/after source snapshot capture.
2. RED: JDWP contract/compiler tests reject a tracepoint without its exact `SourceAnchor`, while plan JSON has no global `sourceFingerprintSha256`.
3. RED: JDWP core tests prove plan execution does not capture a module snapshot before or after collection.
4. RED: evidence tests prove Context contributes identity/provenance only; remove facts claiming source/input/POM sameness from the old snapshot.
5. Run `mvn -pl static-analysis,debug-plan-engine,jdwp-collector-adapter,trace-validator,evidence-engine,ada-core -am test`; expected RED is removed constructor/field mismatch.
6. GREEN: remove `SourceSnapshotReader` injection and comparison paths; resolve adapter/target/module from Case + Project registration.
7. GREEN: remove the JDWP global source field from Java and Schema, keep tracepoint anchors and the collector's existing line/class/method validation.
8. GREEN: update evidence sources to accept `ContextRecord` and stop emitting old snapshot-derived facts.
9. REFACTOR: do not redesign JDWP capture values, budgets or process orchestration in this task.
10. Verify: run the same Maven command and `rg -n "SourceSnapshotReader|sourceFingerprintSha256" ada-core debug-plan-engine jdwp-collector-adapter evidence-engine trace-validator`.
11. Audit: any remaining `sourceFingerprintSha256` belongs neither to Context nor CodePath/JDWP; all JDWP tracepoint anchors remain covered by tests.
12. Cluster verification: run `mvn test`, fix all Context-migration regressions, run `git diff --check`, then commit Tasks 2–5 together as `refactor: simplify context lifecycle`.

## Task 6: Define exact CodePath Plan v2 and simplify the method catalog

**Files:**

- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodCatalog.java`
- Delete `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/PackageCensusEntry.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodSelector.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CollectionBudget.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CodePathCollectionPlan.java`
- Modify `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/JavaSourceCallGraphAnalyzer.java`
- Modify `static-analysis/src/main/java/org/example/algorithmdebug/staticanalysis/CatalogJsonSizeBudget.java`
- Modify `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanRequest.java`
- Modify `debug-plan-engine/src/main/java/org/example/algorithmdebug/plan/CodePathPlanCompiler.java`
- Replace `schemas/analysis/method-catalog-v1.schema.json` with `schemas/analysis/method-catalog-v2.schema.json`
- Replace `schemas/collection/codepath-plan-v1.schema.json` with `schemas/collection/codepath-plan-v2.schema.json`
- Modify contract, static-analysis and plan-engine tests for these types

**Public contracts:**

```java
public record MethodSelector(
        String methodKey,
        String className,
        String methodName,
        String descriptor) {}

public record CollectionBudget(
        long maxEvents,
        long maxBytes,
        long timeoutMillis) {}

public record CodePathCollectionPlan(
        String schemaVersion,
        PlanId planId,
        CaseId caseId,
        ContextId contextId,
        AnalysisId analysisId,
        TargetTest targetTest,
        List<MethodSelector> selectors,
        CollectionBudget budget,
        String rationale,
        Instant createdAt) {}
```

**TDD steps:**

1. RED: contract JSON tests require exactly the v2 fields and reject source hashes, package prefixes/scopes, event estimates and `maxCallDepth` as unknown.
2. RED: method catalog tests remove global source hash/package census but retain exact entry `SourceAnchor`, stable `methodKey`, bounded entries/edges/warnings and completeness.
3. RED: compiler tests accept selectors across packages and overloaded methods, sort by `methodKey`, reject duplicates, unknown keys, empty lists and 51 selectors.
4. RED: add a regression test proving compiler output does not depend on unrelated source files or package counts.
5. Run `mvn -pl ada-contracts,static-analysis,debug-plan-engine -am test`; expected RED is constructor/Schema/compiler mismatch.
6. GREEN: implement v2 records and schemas; set selector default 20/hard 50 and preserve existing event/byte/time limits.
7. GREEN: remove package census production and JSON sizing; analyzer still walks the bounded module sources needed to build the call catalog.
8. GREEN: compile selected method keys directly to exact selectors; do not derive a common package.
9. REFACTOR: centralize stable selector ordering in one comparator and avoid a new selector hierarchy.
10. Verify: `mvn -pl ada-contracts,static-analysis,debug-plan-engine -am test`.
11. Audit: `rg -n "PackageCensusEntry|packagePrefixes|captureScope|estimatedPackageEvents|maxCallDepth|sourceSha256" ada-contracts static-analysis debug-plan-engine schemas/analysis schemas/collection` returns no active CodePath contract code.
12. Checkpoint: do not commit yet; Tasks 6–9 are one cross-module CodePath v2 migration.

## Task 7: Make the launcher read the archived plan and filter before writing

**Files:**

- Modify `tools/code-path-tracer-junit-launcher/pom.xml`
- Modify `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/LauncherArguments.java`
- Create `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/CodePathPlanReader.java`
- Create `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/PlannedTraceEventGenerator.java`
- Modify `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/ExternalJUnitTraceLauncher.java`
- Modify `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/TraceJsonlSink.java`
- Modify `tools/code-path-tracer-junit-launcher/src/main/java/org/example/algorithmdebug/codepath/launcher/LauncherSummary.java`
- Modify/add launcher unit and integration tests under `tools/code-path-tracer-junit-launcher/src/test/java/org/example/algorithmdebug/codepath/launcher/`

**Launcher interface and raw shape:**

```text
java ... ExternalJUnitTraceLauncher \
  --plan D:\ada-workspace\cases\case-001\collections\collection-001\request\plan.json \
  --trace D:\ada-workspace\cases\case-001\collections\collection-001\raw\codepath.jsonl

{"eventId":1,"eventType":"METHOD_ENTER","depth":7,
 "className":"example.Algorithm","methodName":"solve","descriptor":"()V"}
```

```java
boolean matches(AdviceData event) {
    return selectors.contains(new MethodIdentity(
            event.getClassName(), event.getMethodName(), event.getDescriptor()));
}
```

**TDD steps:**

1. RED: argument/reader tests require only `--plan` and `--trace`; reject duplicate flags, nonregular/symlink Plan, Plan above 1 MiB, v1, unknown fields, invalid budgets and a trace path outside the allowed collection directory.
2. RED: generator tests verify exact class/name/descriptor matching, overloaded-method exclusion, cross-package inclusion and no event emission for unselected Advice data.
3. RED: verify event IDs are contiguous only for retained events and every raw line has descriptor but no arguments, return value or thread name.
4. RED: verify all selected events from one thread succeed; a second selected-method thread yields `CODEPATH_MULTIPLE_THREADS_UNSUPPORTED`, stops evidence recording and leaves the target result independently classifiable.
5. RED: verify event and byte truncation stops new writes while the UT continues; timeout remains process-level in the adapter.
6. RED: fixture tests cover balanced calls, recursion, selected parent/unselected middle/selected child, assertion failure and algorithm exception.
7. Run `mvn -Pcodepath-launcher -pl tools/code-path-tracer-junit-launcher -am test`; expected RED is missing plan reader/generator and old CLI.
8. GREEN: parse Plan v2 with the shared strict mapper/contracts, build an immutable selector set, and pass `PlannedTraceEventGenerator` to upstream tracing before `TraceJsonlSink`.
9. GREEN: keep only retained-event counters/bytes; expose structured launcher outcome and reason without throwing away already flushed raw lines.
10. REFACTOR: keep exact matching and single-thread state in the generator; keep JSONL/atomic close behavior in the sink.
11. Verify: rerun the Maven command twice to catch leaked static tracer state.
12. Audit: no package include flag or `@AllArguments` value is serialized; no shell command is built from Plan values; upstream dependency remains locked.
13. Checkpoint: do not commit yet; retain launcher verification output for the final CodePath cluster audit.

## Task 8: Remove post-filtering and simplify CodePath SPI, manifest and core archival

**Files:**

- Delete `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/MethodPathJsonlFilter.java`
- Delete `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/MethodPathFilterResult.java`
- Delete `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/MethodPathEvent.java` if the shared Raw v2 contract fully replaces it
- Delete `method-path-codepathtracer/src/test/java/org/example/algorithmdebug/codepath/MethodPathJsonlFilterTest.java`
- Modify `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/CodePathCommandFactory.java`
- Modify `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/CodePathProcessCollector.java`
- Modify `method-path-codepathtracer/src/main/java/org/example/algorithmdebug/codepath/CodePathLauncherSummary.java`
- Modify `method-path-spi/src/main/java/org/example/algorithmdebug/methodpath/MethodPathCollectionRequest.java`
- Modify `method-path-spi/src/main/java/org/example/algorithmdebug/methodpath/MethodPathCollectionResult.java`
- Modify `method-path-spi/src/main/java/org/example/algorithmdebug/methodpath/MethodPathManifest.java`
- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodPathCollectionRecord.java`
- Replace `schemas/collection/method-path-manifest-v1.schema.json` with `schemas/collection/method-path-manifest-v2.schema.json`
- Modify `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Modify `ada-core/src/main/java/org/example/algorithmdebug/core/CollectionApplicationService.java`
- Modify tests in `method-path-spi`, `method-path-codepathtracer`, `case-management` and `ada-core`

**Manifest simplification:**

```text
Keep: identities, tool version/hash, plan hash, completion/stage, process facts,
capturedEventCount, capturedBytes, rawSha256, truncation reason,
target failure, tool failure, stdout/stderr/raw paths, timestamps.
Delete: package/capture/evidence scope, match precision,
raw-versus-filtered double counts, filtered path and filtered hash.
```

**TDD steps:**

1. RED: SPI/Schema tests reject former package/filtered/match-precision fields and require one raw artifact/hash/count set.
2. RED: command factory test proves the archived Plan path is passed to the launcher and no package prefix is emitted.
3. RED: collector tests cover success, zero hits, truncation, target failure, tool failure, malformed summary and timeout; all preserve bounded logs/manifest/raw where present.
4. RED: core/repository tests prove the same Plan file is archived before launch, its SHA is in Manifest, and the raw file is never rewritten by an adapter filter.
5. RED: baseline tests retain the current rule: CodePath evidence is usable only when its Gantt content hash or failure fingerprint matches the current Context's uninstrumented reference.
6. Run `mvn -Pcodepath-launcher -pl method-path-spi,method-path-codepathtracer,case-management,ada-core -am test`; expected RED is old filtered-result shape.
7. GREEN: launch from Plan, capture one raw stream, create v2 Manifest, and archive append-only artifacts.
8. GREEN: delete filter types/tests only after no reference remains; do not leave deprecated aliases.
9. REFACTOR: keep process supervision in the adapter and identity/baseline orchestration in core.
10. Verify: rerun the Maven command.
11. Audit: `rg -n "MethodPathJsonlFilter|filteredTrace|rawEventCount|filteredEventCount|PACKAGE_SUPERSET|matchPrecision" method-path-spi method-path-codepathtracer ada-core case-management schemas/collection` has no active hits.
12. Checkpoint: do not commit yet; Task 9 completes the CodePath contract migration.

## Task 9: Simplify single-thread normalization, validation and evidence

**Files:**

- Modify `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/MethodPathSummary.java`
- Replace `schemas/trace/method-path-summary-v1.schema.json` with `schemas/trace/method-path-summary-v2.schema.json`
- Modify `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/CodePathNormalizationInput.java`
- Modify `trace-normalizer/src/main/java/org/example/algorithmdebug/normalizer/MethodPathNormalizer.java`
- Modify `trace-validator/src/main/java/org/example/algorithmdebug/validator/MethodPathValidationInput.java`
- Modify `trace-validator/src/main/java/org/example/algorithmdebug/validator/CollectionEvidenceValidator.java`
- Modify `evidence-engine/src/main/java/org/example/algorithmdebug/evidence/EvidenceBundleBuilder.java`
- Modify affected tests in `ada-contracts`, `trace-normalizer`, `trace-validator` and `evidence-engine`

**Normalization rule:**

```text
one stream -> one stack
METHOD_ENTER(selected method) -> push exact method identity
METHOD_EXIT(same method) -> pop
selected child under selected ancestor -> NEAREST_SELECTED_ANCESTOR
EOF with open frames -> anomaly + incomplete fact
zero selected events -> MISSING_EVIDENCE, never “method did not execute”
```

**TDD steps:**

1. RED: summary JSON tests remove `threadName` and `matchPrecision`, require descriptor-bearing identities and preserve provenance line/event IDs.
2. RED: normalizer tests cover balanced nesting, recursion, nearest selected ancestor, mismatched exit, open frames after target exception, zero events, invalid descriptor, overlong line and output budget.
3. RED: validator tests reject identity/Plan/raw hash mismatches, malformed/truncated/multithread tool results and changed baseline; accept a target failure when its failure fingerprint is stable and raw evidence before failure is valid.
4. RED: evidence tests classify zero hits/truncation/open frames as `MISSING_EVIDENCE` or bounded validator conclusions, never confirmed absence or a business root cause.
5. Run `mvn -pl ada-contracts,trace-normalizer,trace-validator,evidence-engine -am test`; expected RED is old per-thread/match-precision shape.
6. GREEN: replace the map of thread stacks with one `Deque`, stream Raw v2, and compute bounded method counts/ancestor edges/anomalies.
7. GREEN: remove source snapshot and package-match validation; keep Plan/Manifest/Raw hash, identity, completion, baseline and provenance checks.
8. REFACTOR: do not infer direct calls through unselected methods; name the relationship `NEAREST_SELECTED_ANCESTOR` consistently.
9. Verify: rerun the Maven command.
10. Audit: no full raw file or unbounded object graph is loaded; all conclusions retain allowed classifications.
11. Cluster verification: run `mvn -Pcodepath-launcher test`, fix all CodePath-migration regressions, run `git diff --check`, then commit Tasks 6–9 together as `refactor: collect exact single-thread codepath evidence`.

## Task 10: Prove the end-to-end scenario, measure cost and synchronize all user documentation

**Status (2026-08-19): IN PROGRESS.** 分层测试已覆盖 Context 复用/显式新建、Run/Collection
变化、目标失败、工具失败、零命中、截断和第二线程，但 A～G 尚未全部集中为 `integration-tests`
模块中的端到端用例；无采集/旧包级/精确方法三组同条件性能测量也尚未完成，不能关闭本 Task。

**Files:**

- Modify/add integration fixtures under `integration-tests/src/test/`
- Modify `method-path-codepathtracer/src/test/java/org/example/algorithmdebug/codepath/CodePathRealProjectSmokeTest.java`
- Modify `docs/architecture/tool-validation-baseline.md`
- Modify `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify `README.md`
- Modify `ada-contracts/README.md`
- Modify `case-management/README.md`
- Modify `ada-core/README.md`
- Create `method-path-spi/README.md`
- Create `method-path-codepathtracer/README.md`
- Modify `schemas/README.md`
- Modify `skills/algorithm-debug/SKILL.md`
- Modify `integrations/opencode/README.md`
- Modify `docs/designs/2026-08-18-context-codepath-simplification-design.md`
- Add/update Eval cases in the repository's existing Eval location; do not create a new framework

**End-to-end scenarios:**

```text
A. case open -> initial Context -> baseline UT -> plan -> exact CodePath -> same Gantt -> usable evidence
B. same Case question -> reuse Context -> reuse evidence without rerun
C. explicit target change -> case open --context-mode new -> new Context -> new baseline
D. unexpected normal Run CHANGED -> no Context mutation; model sees comparison and decides next step
E. collection Run CHANGED -> artifacts retained, evidence unusable, no Context mutation
F. target exception/assertion/input failure -> target diagnostic retained; Agent remains operational
G. second selected-method thread -> structured unsupported evidence, target outcome retained separately
```

**Steps:**

1. RED: add integration tests for scenarios A–G using temporary Maven fixtures and deterministic IDs/clocks; assert the complete append-only directory layout.
2. RED: update real-project smoke to choose explicit method selectors and assert every raw event belongs to the Plan and the uninstrumented/instrumented Gantt content hashes match.
3. GREEN: fix only cross-module integration gaps discovered by those tests; add focused regression tests before each fix.
4. Verify affected reactor: `mvn -Pcodepath-launcher test`.
5. Verify a clean reactor: `mvn -Pcodepath-launcher clean test`. If Maven cannot read `C:\Users\zhao1k\.m2` or reach required repositories, request the narrow permission and rerun; do not substitute historical Surefire reports for a fresh result.
6. Run the real local smoke with configured properties:

   ```powershell
   mvn -Pcodepath-launcher -pl method-path-codepathtracer -am `
     -Dada.codepath.module=D:\javacode\hellomvn `
     -Dada.codepath.jar=D:\javacode\algorithm-debug-agent\tools\code-path-tracer-junit-launcher\target\code-path-tracer-junit-launcher-0.1.0-SNAPSHOT.jar `
     -Dtest=CodePathRealProjectSmokeTest test
   ```

7. Measure three runs each for uninstrumented baseline, old package fixture (from the pre-change commit/worktree only) and exact-plan collection. Record median wall time, raw event count, raw bytes, truncation and Gantt/failure fingerprint status; do not claim a percentage improvement without these values.
8. Update Skill and usage docs with the simple decision rule: reuse Context unless an explicit target code/UT/input change is known; if known, create a new Context; Gantt change alone is analysis evidence, not an automatic split.
9. Update Schema index/examples, architecture/plans/status and the design completion record with actual commits, commands, results, performance values and known upstream Advice limitation.
10. Run `rg -n "context-snapshot-v1|codepath-plan-v1|method-path-manifest-v1|method-path-summary-v1|PACKAGE_SUPERSET|MethodPathJsonlFilter|contextChanged" . --glob '!target/**' --glob '!.git/**'`; every remaining hit must be an explicitly marked historical/superseded reference or be removed.
11. Run `git diff --check`, inspect `git status --short`, review every changed production file against the spec and run the full test command once more after audit fixes.
12. Commit documentation/eval changes separately if large: `test: verify explicit context codepath workflow` and `docs: document simplified debug workflow`.

## Completion Gate

The work is complete only when all of the following are true:

- Context creation is explicit and no Context/source/input/POM scanner remains in production code.
- `case open` defaults to reuse and exposes one explicit new-Context option to OpenCode.
- CodePath Plan v2 contains only exact methods and hard event/byte/time budgets.
- Launcher filters by class/name/descriptor before JSON formatting/writing and rejects a second selected-method thread.
- Only one raw CodePath artifact is archived; no post-filter step or package compatibility field remains.
- Normalization is single-stack, bounded and honest about nearest selected ancestors, open frames and zero hits.
- Dynamic evidence still passes the same-Context uninstrumented Gantt/failure-fingerprint gate.
- JDWP still validates exact tracepoint `SourceAnchor` while performing no whole-module Context fingerprint scan.
- Changed v1 schemas/readers are removed; the documented local development workspace rebuild succeeds.
- A fresh Maven reactor test, real target smoke, performance measurements, documentation audit and code audit have been completed and recorded.
