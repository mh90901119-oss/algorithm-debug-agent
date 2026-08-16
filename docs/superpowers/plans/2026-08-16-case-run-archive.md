# Case Run Archive Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建成可持久化/续接 Case、按需执行真实 Maven/JUnit UT，并把结构化 RunOutcome 与原始产物追加归档到外部 Workspace 的首个可用纵向切片。

**Architecture:** CLI 只解析命令并输出 `ToolResponse`；Core 分离 `case open/inspect` 与 `run execute`；Case Management 原子保存 Case/Context/Analysis/Run 并重建有界 Digest；Debug Harness 只解析本次新增或变化的 Surefire 报告，并独立保留进程、测试和 Gantt 事实。具体算法通过 ServiceLoader Adapter 注入，Core 不依赖 Wafer Demo 实现。

**Tech Stack:** Java 21、Maven、JUnit 5、Jackson 2.17.2、JSON Schema Draft 2020-12、Windows PowerShell 验收。

## Global Constraints

- 所有生产行为按 Red-Green-Refactor：先观察测试因缺失行为失败，再写最小实现。
- Case、Context、Analysis、Run 和 Artifact 只追加、不可覆盖；终态控制文档必须原子 create-new。
- 目标算法生产源码、UT、POM 和输入文件不得被 Agent 修改；Maven 正常生成的 `target/` 和算法输出允许变化。
- stdout/stderr 各默认 10 MiB；Surefire XML 10 MiB；Gantt 64 MiB；控制 JSON 1 MiB；CLI ToolResponse 1 MiB。
- Context 仅扫描 `pom.xml`、`src/main/java/**/*.java`、`src/test/java/**/*.java` 和 Adapter 定位的输入；不跟随符号链接。
- LLM 决定是否运行及运行次数；代码不自动运行、不自动重试、不推断业务根因。
- 当前工作区已有未提交修改；每次只精确暂存本任务文件并检查 staged diff。

---

### Task 1: Versioned Case and Run Contracts

**Files:**
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SnapshotCompleteness.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InputSnapshotStatus.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SourceSnapshot.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/InputSnapshot.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/BuildSnapshot.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseManifest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ContextSnapshot.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/AnalysisRequest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunRequest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/ArchiveWarning.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseDigest.java`
- Create: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/CaseOpenResult.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/RunOutcomeSummary.java`
- Modify: `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/SchemaVersions.java`
- Create: `schemas/case/case-manifest-v1.schema.json`
- Create: `schemas/case/context-snapshot-v1.schema.json`
- Create: `schemas/case/analysis-request-v1.schema.json`
- Create: `schemas/execution/run-request-v1.schema.json`
- Create: `schemas/case/case-digest-v1.schema.json`
- Modify: `schemas/execution/run-outcome-summary-v1.schema.json`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveContractsTest.java`
- Test: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/CaseArchiveSchemaTest.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunOutcomeSummaryTest.java`
- Modify: `ada-contracts/src/test/java/org/example/algorithmdebug/contracts/RunOutcomeSummaryJsonTest.java`

**Interfaces:**
- Produces: immutable V1 contracts used by every later task.
- `RunOutcomeSummary` constructor becomes `(schemaVersion, eventType, caseId, contextId, analysisId, runId, processOutcome, testOutcome, ganttOutcome, targetFailure, agentFailure, comparisonOutcome, comparisonSummary, artifacts)` with no `latestRunForAnalysis`.
- `RunRequest` stores `executionMode="UNINSTRUMENTED"`; it does not promote the run to Baseline.

- [ ] **Step 1: Write failing contract tests**

```java
@Test
void runOutcomeMustNotPersistQueryTimeLatestFlag() throws Exception {
    String json = mapper.writeValueAsString(sampleRunOutcome());
    assertFalse(json.contains("latestRunForAnalysis"));
}

@Test
void contextFingerprintMustRejectNonSha256Value() {
    assertThrows(IllegalArgumentException.class, () -> new ContextSnapshot(
            SchemaVersions.CONTEXT_SNAPSHOT, caseId, contextId, projectId, targetTest,
            "UNAVAILABLE", source, input, build, SnapshotCompleteness.COMPLETE,
            "not-a-hash", List.of(), instant));
}
```

- [ ] **Step 2: Run RED verification**

Run: `mvn -pl ada-contracts -am -Dtest=CaseArchiveContractsTest,RunOutcomeSummaryTest,RunOutcomeSummaryJsonTest test`

Expected: FAIL because the new contracts/constants do not exist and the old RunOutcome constructor still requires `latestRunForAnalysis`.

- [ ] **Step 3: Implement minimal immutable contracts and schema changes**

```java
public record CaseManifest(
        String schemaVersion, CaseId caseId, ProjectId projectId,
        TargetTest targetTest, String initialQuestion, Instant createdAt) { }

public record CaseDigest(
        String schemaVersion, CaseId caseId, ProjectId projectId, TargetTest targetTest,
        ContextId latestContextId, AnalysisId latestAnalysisId, String latestQuestionExcerpt,
        Optional<RunId> latestRunId, List<RunOutcomeSummary> recentRuns,
        List<RunId> incompleteRuns, List<ArchiveWarning> archiveWarnings,
        int contextCount, int analysisCount, int runCount, boolean truncated) { }
```

Use existing `ContractChecks`; cap question at 65,536 chars, excerpts/warnings at 2,048 chars, IDs at existing opaque-ID limits, and defensively copy every list.

- [ ] **Step 4: Add literal JSON examples and validate against all new schemas**

Tests must load each schema from `../schemas`, validate a hand-authored valid JSON object, and assert an invalid object with an unknown property is rejected.

- [ ] **Step 5: Run GREEN verification and contract audit**

Run: `mvn -pl ada-contracts -am test`

Expected: all ada-contracts tests pass; `rg -n "latestRunForAnalysis" ada-contracts schemas` returns no production/schema occurrence.

- [ ] **Step 6: Commit Task 1 exact files**

```text
feat: add case archive contracts
```

### Task 2: Append-only Case Archive Repository and Digest

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveLayout.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArchiveRepository.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseDigestReader.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseWorkspace.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/BoundedDocumentMapper.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/ImmutableArtifactStore.java`
- Modify: `case-management/pom.xml`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseArchiveRepositoryTest.java`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseDigestReaderTest.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseWorkspaceTest.java`
- Modify: `case-management/src/test/java/org/example/algorithmdebug/casecore/ImmutableArtifactStoreTest.java`

**Interfaces:**
- Consumes: Task 1 contracts.
- Produces: `CaseArchiveRepository.createCase/createContext/createAnalysis/startRun/completeRun`, typed read methods, and `CaseDigestReader.read(CaseId)`.
- `CaseArchiveLayout` receives a trusted project cases root and derives paths only from validated IDs.

- [ ] **Step 1: Write failing repository tests**

```java
@Test
void runWithoutOutcomeIsReportedAsIncomplete() {
    repository.createCase(caseManifest);
    repository.createContext(contextSnapshot);
    repository.createAnalysis(analysisRequest);
    repository.startRun(runRequest);

    CaseDigest digest = digestReader.read(caseId);

    assertEquals(List.of(runId), digest.incompleteRuns());
    assertTrue(digest.latestRunId().isEmpty());
}

@Test
void laterRunDoesNotRewriteEarlierOutcome() {
    byte[] firstBytes = Files.readAllBytes(firstOutcomePath);
    repository.completeRun(secondOutcome);
    assertArrayEquals(firstBytes, Files.readAllBytes(firstOutcomePath));
}
```

- [ ] **Step 2: Run RED verification**

Run: `mvn -pl case-management -am -Dtest=CaseArchiveRepositoryTest,CaseDigestReaderTest test`

Expected: FAIL because archive repository/layout/digest do not exist.

- [ ] **Step 3: Implement layout and atomic repository**

`createCase` creates the fixed directories then atomically writes `case.json`; child creates use a new ID directory followed by one terminal document. Register `Jdk8Module` in `BoundedDocumentMapper` and add the matching locked Jackson datatype dependency because persisted RunOutcome contains `Optional`.

- [ ] **Step 4: Implement bounded Digest reconstruction**

Sort valid entries by `(createdAt, id)`, return only the newest 20 analyses and Run outcomes, count all entries, and report child-document corruption as `ArchiveWarning`. Invalid `case.json` remains fatal. A missing `run-outcome.json` is an incomplete Run, not an invented failure outcome.

- [ ] **Step 5: Add path/symlink/atomicity regression tests**

Tests must prove ID path escape is rejected, terminal documents cannot be overwritten, symbolically linked child directories are not followed, and `ImmutableArtifactStore` fails when atomic move is unavailable rather than silently using a weaker move.

- [ ] **Step 6: Run GREEN verification and audit**

Run: `mvn -pl case-management -am test`

Expected: all dependency and case-management tests pass; audit public APIs for Chinese Javadoc and confirm no mutable `current.json` or registry file exists.

- [ ] **Step 7: Commit Task 2 exact files**

```text
feat: persist append-only case archives
```

### Task 3: Bounded Context Snapshot and Case Session

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextSnapshotRequest.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ContextSnapshotBuilder.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseSessionService.java`
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/OpaqueIdGenerator.java`
- Remove after migration: `case-management/src/main/java/org/example/algorithmdebug/casecore/ManagedCase.java`
- Remove after migration: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseResolutionService.java`
- Remove after migration: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseResolution.java`
- Remove after migration: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseResolutionAction.java`
- Remove after migration: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseIntent.java`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/ContextSnapshotBuilderTest.java`
- Test: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseSessionServiceTest.java`
- Remove after migration: `case-management/src/test/java/org/example/algorithmdebug/casecore/CaseResolutionServiceTest.java`

**Interfaces:**
- Consumes: archive repository and Task 1 snapshot contracts.
- Produces: `CaseSessionService.open(NewOrExistingCaseRequest): CaseOpenResult`.
- `ContextSnapshotRequest` contains canonical module/repository paths, target test, Adapter identity, and an input probe with `PRESENT/MISSING/NOT_APPLICABLE/UNRESOLVED`.

- [ ] **Step 1: Write failing Context tests**

```java
@Test
void sourceHashChangesWhenJavaSourceChanges() throws Exception {
    ContextSnapshot first = builder.build(request());
    Files.writeString(mainSource, "class A { int value = 2; }");
    ContextSnapshot second = builder.build(requestWithNewContextId());
    assertNotEquals(first.fingerprintSha256(), second.fingerprintSha256());
}

@Test
void incompleteSnapshotNeverReusesExistingContext() {
    CaseOpenResult result = service.open(existingCaseRequestWithExceededBudget());
    assertTrue(result.contextChanged());
    assertNotEquals(existingContextId, result.contextId());
}
```

- [ ] **Step 2: Run RED verification**

Run: `mvn -pl case-management -am -Dtest=ContextSnapshotBuilderTest,CaseSessionServiceTest test`

Expected: FAIL because snapshot/session services do not exist.

- [ ] **Step 3: Implement bounded streaming snapshot**

Walk only allowlisted roots without following links; sort normalized relative paths; stream SHA-256 over `(relativePath, size, contentHash)` tuples. Stop at 20,000 files, 512 MiB total, 16 MiB per file, or injected 10-second deadline and return `INCOMPLETE` with stable warnings.

- [ ] **Step 4: Implement explicit Case continuation rules**

No case ID creates Case + Context + Analysis. Existing case ID validates Project/UT, compares only complete compatible fingerprints, reuses equal Context, otherwise appends Context; every call appends Analysis. The service never runs Maven.

- [ ] **Step 5: Remove duplicate in-memory decision model after tests migrate**

Delete the five obsolete resolution types only after all semantics have coverage in `CaseSessionServiceTest`; retain Baseline-specific classes untouched.

- [ ] **Step 6: Run GREEN verification and audit**

Run: `mvn -pl case-management -am test`

Expected: all tests pass; scan code to confirm no `Files.walk` outside fixed allowlists and no source bytes retained in collections.

- [ ] **Step 7: Commit Task 3 exact files**

```text
feat: open persistent case analyses
```

### Task 4: Current-run Surefire Facts and RunOutcome Assembly

**Files:**
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireReportSnapshot.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireReportSnapshotter.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireTestResult.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireTestResultReader.java`
- Create: `debug-harness/src/main/java/org/example/algorithmdebug/harness/RunOutcomeAssembler.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/SurefireDiagnosticReader.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunner.java`
- Modify: `debug-harness/src/main/java/org/example/algorithmdebug/harness/ScheduleRunResult.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/SurefireReportSnapshotterTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/SurefireTestResultReaderTest.java`
- Test: `debug-harness/src/test/java/org/example/algorithmdebug/harness/RunOutcomeAssemblerTest.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/SurefireDiagnosticReaderTest.java`
- Modify: `debug-harness/src/test/java/org/example/algorithmdebug/harness/ScheduleProducingTestRunnerTest.java`

**Interfaces:**
- Consumes: Task 1 RunOutcome and existing Harness result types.
- Produces: exact target test result from a changed report and a pure assembler.
- `SurefireReportSnapshotter.changedTargetReports(before, after, TargetTest)` returns only new/content-changed files.

- [ ] **Step 1: Write stale-report RED test**

```java
@Test
void unchangedOldReportCannotDescribeCurrentCompileFailure() throws Exception {
    SurefireReportSnapshot before = snapshotter.snapshot(reports, targetTest);
    SurefireReportSnapshot after = snapshotter.snapshot(reports, targetTest);
    assertTrue(snapshotter.changedTargetReports(before, after).isEmpty());
}
```

- [ ] **Step 2: Write outcome-matrix RED tests**

Use literal fixtures for PASS, assertion `<failure>`, exception `<error>`, absent report with compile marker, absent report with no-test marker, timeout, process-start failure, and Gantt post-processing failure. Assert all six result dimensions independently.

- [ ] **Step 3: Run RED verification**

Run: `mvn -pl debug-harness -am -Dtest=SurefireReportSnapshotterTest,SurefireTestResultReaderTest,RunOutcomeAssemblerTest test`

Expected: FAIL because the report snapshot/result/assembler types do not exist.

- [ ] **Step 4: Implement secure current-run report parsing**

Hash candidate XML before/after; parse only changed target files with the existing XXE protections and 10 MiB limit. Return `PASSED` only when the exact target testcase is present without failure/error/skipped; never infer pass solely from Maven exit code.

- [ ] **Step 5: Implement pure RunOutcome mapping**

Map process completion, exact Surefire fact, bounded Maven stage markers, Gantt outcome and Agent diagnostic into `RunOutcomeSummary`; set comparison to `NOT_COMPARED`. Do not read files or mutate the Workspace in the assembler.

- [ ] **Step 6: Run GREEN verification and audit**

Run: `mvn -pl debug-harness -am test`

Expected: all Harness tests pass; existing timeout/process-tree/log-budget tests remain green.

- [ ] **Step 7: Commit Task 4 exact files**

```text
feat: assemble current run outcomes
```

### Task 5: Core Case and Run Orchestration

**Files:**
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/AdapterCatalog.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/CaseApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/RunApplicationService.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/RunArtifactArchiver.java`
- Create: `ada-core/src/main/java/org/example/algorithmdebug/core/CaseRunException.java`
- Modify: `ada-core/src/main/java/org/example/algorithmdebug/core/ControlPlaneServices.java`
- Modify: `ada-core/pom.xml`
- Modify: `adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoAdapter.java`
- Modify: `adapters/wafer-demo-adapter/src/main/java/org/example/algorithmdebug/adapter/waferdemo/WaferInputLocator.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/AdapterCatalogTest.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/CaseApplicationServiceTest.java`
- Test: `ada-core/src/test/java/org/example/algorithmdebug/core/RunApplicationServiceTest.java`
- Modify: `adapters/wafer-demo-adapter/src/test/java/org/example/algorithmdebug/adapter/waferdemo/WaferDemoAdapterTest.java`

**Interfaces:**
- Consumes: Case services, ProjectRegistration, Adapter SDK, Harness.
- Produces: Core `openCase`, `inspectCase`, and `executeRun` Use Cases.
- Adapter Catalog receives an immutable list from the composition root; it does not call ServiceLoader itself.

- [ ] **Step 1: Write Adapter and orchestration RED tests**

```java
@Test
void missingInputDoesNotPreventWaferProjectInspection() throws Exception {
    Files.delete(inputFile);
    assertDoesNotThrow(() -> adapter.inspect(projectRoot));
}

@Test
void executeCreatesRunRequestBeforeExternalProcessStarts() {
    processPort.whenStarted(() -> assertTrue(Files.isRegularFile(runRequestPath)));
    service.execute(request);
}
```

- [ ] **Step 2: Run RED verification**

Run: `mvn -pl ada-core,adapters/wafer-demo-adapter -am -Dtest=AdapterCatalogTest,CaseApplicationServiceTest,RunApplicationServiceTest,WaferDemoAdapterTest test`

Expected: FAIL because Core services do not exist and Wafer inspect still rejects missing input.

- [ ] **Step 3: Implement Adapter Catalog and Case Use Cases**

Stable-sort adapters by ID; explicit ID must match exactly; auto-selection probes all adapters and returns deterministic not-found/ambiguous errors. Convert `ADAPTER_INPUT_NOT_FOUND` into Context `MISSING`, other locator failures into `UNRESOLVED`.

- [ ] **Step 4: Implement Run transaction**

Validate Project/Case/Analysis/Context, allocate Run ID, create run-request, build launch spec, route logs/Gantt to Run raw paths, snapshot Surefire before/after, execute Harness, archive current report, assemble outcome, and atomically create run-outcome. Catch process-start and post-processing failures into Agent diagnostics when a trustworthy outcome can still be written.

- [ ] **Step 5: Run GREEN verification and audit**

Run: `mvn -pl ada-core,adapters/wafer-demo-adapter -am test`

Expected: tests pass; audit dependency direction (`ada-core` has no dependency on wafer adapter), cause preservation, process cleanup, and no automatic retry.

- [ ] **Step 6: Commit Task 5 exact files**

```text
feat: orchestrate case test runs
```

### Task 6: Stable CLI and Adapter Packaging

**Files:**
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommand.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliArguments.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/CliCommandExecutor.java`
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify: `algorithm-debug-cli/pom.xml`
- Test: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliArgumentsTest.java`
- Test: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/CliCommandExecutorTest.java`
- Test: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/AdaMainTest.java`
- Create: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/ShadedAdapterDiscoveryIT.java`

**Interfaces:**
- Consumes: Task 5 services.
- Produces: `case open`, `case inspect`, `run execute` JSON commands.
- CLI composition root loads `ServiceLoader<TargetProjectAdapter>` and passes the immutable list to `ControlPlaneServices`.

- [ ] **Step 1: Write CLI RED tests**

```java
@Test
void parsesCaseOpenWithoutRunningTest() {
    CliCommand command = CliArguments.parse(new String[]{
            "case", "open", "--workspace", workspace.toString(),
            "--project-id", "demo", "--test", "a.b.Test#case1",
            "--question-file", question.toString()});
    assertInstanceOf(CliCommand.CaseOpen.class, command);
}
```

Also assert different-UT reuse returns exit 3, malformed question files return exit 2/3 as specified, and stdout is one JSON document without target log content.

- [ ] **Step 2: Run RED verification**

Run: `mvn -pl algorithm-debug-cli -am -Dtest=CliArgumentsTest,CliCommandExecutorTest,AdaMainTest test`

Expected: FAIL because new sealed command variants and services are not wired.

- [ ] **Step 3: Implement strict parsing and command execution**

Reject unknown/duplicate/missing flags; parse the selector using `TargetTest`; read question as strict UTF-8 regular file capped at 64 KiB. Extend safe domain-message mapping without exposing paths, stack traces, or exception messages.

- [ ] **Step 4: Package Adapter service metadata**

Add Wafer Demo Adapter as CLI runtime dependency and Maven Shade `ServicesResourceTransformer`; package the fat JAR and execute a discovery test against the produced JAR rather than grepping source metadata.

- [ ] **Step 5: Run GREEN verification and audit**

Run: `mvn -pl algorithm-debug-cli -am test`

Run: `mvn -pl algorithm-debug-cli -am package`

Expected: all tests pass and the shaded CLI discovers exactly one Wafer Demo Adapter.

- [ ] **Step 6: Commit Task 6 exact files**

```text
feat: expose case run cli
```

### Task 7: Integration, Documentation, Audit, and Final Verification

**Files:**
- Create: `integration-tests/src/test/java/org/example/algorithmdebug/integration/CaseRunArchiveIntegrationTest.java`
- Create: `integration-tests/src/test/resources/maven-fixtures/README.md`
- Modify: `integration-tests/pom.xml`
- Modify: `README.md`
- Modify: `ada-contracts/README.md`
- Modify: `case-management/README.md`
- Modify: `debug-harness/README.md`
- Modify: `ada-core/README.md`
- Modify: `algorithm-debug-cli/README.md`
- Modify: `schemas/README.md`
- Modify: `docs/architecture/algorithm-debug-agent-module-detailed-design-v1.md`
- Modify: `docs/plans/algorithm-debug-agent-development-plan.md`
- Modify: `docs/superpowers/specs/2026-08-16-case-run-archive-design.md`

**Interfaces:**
- Consumes: complete vertical slice.
- Produces: repeatable isolated failure fixtures, real `hellomvn` acceptance evidence, and accurate current-state documentation.

- [ ] **Step 1: Write isolated integration tests before fixture implementation**

Use temporary Maven projects with locally available JUnit coordinates and literal source files to cover pass, assertion failure, thrown exception, compile failure, test-not-found, and timeout. Assert archived JSON and Artifact hashes, not Maven console wording.

- [ ] **Step 2: Run integration RED verification**

Run: `mvn -pl integration-tests -am -Dtest=CaseRunArchiveIntegrationTest test`

Expected: FAIL until the fixture builder and complete CLI/Core path are connected.

- [ ] **Step 3: Complete fixtures and run GREEN verification**

Run: `mvn -pl integration-tests -am -Dtest=CaseRunArchiveIntegrationTest test`

Expected: all six scenarios pass without network and without absolute developer-machine paths in test data.

- [ ] **Step 4: Execute real hellomvn success acceptance**

Initialize a temporary external Workspace, register `D:\javacode\hellomvn`, open the Wafer reproduction Case, execute it, inspect the Case, and verify RunOutcome plus stdout/stderr/Surefire/Gantt references. Snapshot tracked source/UT/POM/input hashes before and after; they must match. Do not delete or rename the input to manufacture failure.

- [ ] **Step 5: Synchronize current-state documents**

Mark only implemented behavior as implemented; explicitly keep Baseline comparison, Gantt business analysis, CodePathTracer, JDWP, Evidence and OpenCode installer pending. Change the design status to `Implemented` and record actual deviations/commands/counts.

- [ ] **Step 6: Run final module and repository audit**

Run:

```text
mvn test
mvn -pl algorithm-debug-cli -am package
node --test integrations/opencode/test/ada-cli.test.mjs
git diff --check
```

Audit public Chinese Javadoc, Schema examples, dependency graph, path containment, symlink handling, target-source immutability, bounded outputs, exception cause preservation, no automatic retries, and exact staged file list.

- [ ] **Step 7: Commit Task 7 exact files**

```text
test: verify case run archive workflow
```

## Plan Self-review

- Spec coverage: all approved sections map to Tasks 1-7; Baseline/collectors/evidence/OpenCode installation remain explicitly outside the plan.
- Placeholder scan: no implementation step delegates unspecified error handling or testing; code excerpts define the intended API behavior.
- Type consistency: Task 1 contracts feed Tasks 2-6; Core owns orchestration; Adapter remains injected; Harness owns deterministic parsing; Digest owns query-time latest selection.
- TDD: every behavior-changing task begins with named failing tests and a required RED command before production edits.
- Dirty-worktree safety: every task ends with exact-file staging and staged-diff review rather than broad `git add .`.
