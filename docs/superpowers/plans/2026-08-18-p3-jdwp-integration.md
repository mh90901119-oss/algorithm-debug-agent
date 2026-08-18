# P3 JDWP Integration Implementation Plan

> **Execution rule:** Follow Red-Green-Refactor for every task, audit after each module, and do not start P4 before the P3 real smoke and root reactor are green.

**Goal:** Integrate the locked JDWP Batch Collector so a model can create and execute a bounded, archived JDWP plan for methods located by static analysis and CodePath evidence.

**Architecture:** Compile versioned Agent tracepoints from `MethodCatalog` source anchors into the exact current external Collector JSON. Start a suspended Surefire test JVM and Collector as two supervised processes, archive every fact under one Case collection, then reuse source and Baseline gates before exposing evidence as usable.

**Tech Stack:** Java 21, Maven, JUnit 5, Jackson 2.17.2, JDK Process API, external JDWP Collector commit `1ef7d22`.

**Detailed design:** `docs/designs/2026-08-18-p3-jdwp-integration-design.md`

## Global constraints

- No target production source modification and no shell command strings.
- JDWP host is always `127.0.0.1`; user/model cannot override it.
- Current Collector capability is exact: all-or-nothing visible locals, stack and bounded snapshot limits. No local allowlist, projection or sampling.
- Every dynamic run gets new `runId/collectionId`; all writes are create-new.
- Any truncation, source drift, Baseline change, missing Raw lifecycle proof or cleanup uncertainty sets `evidenceUsable=false`.
- No P4 normalization or external Collector P0 implementation in this plan.

## Task 1: Correct and lock the JDWP capability contract

**Files:**

- Create contracts under `ada-contracts/src/main/java/org/example/algorithmdebug/contracts/`
- Create `schemas/collection/jdwp-plan-v1.schema.json`
- Create `schemas/collection/jdwp-collection-request-v1.schema.json`
- Create `schemas/collection/jdwp-manifest-v1.schema.json`
- Modify `ada-contracts/.../SchemaVersions.java`
- Modify `config/toolchain-lock.json`
- Modify `config/collection-limits.yaml`

1. RED: add contract tests for IDs, 1–20 tracepoints, source anchor/line identity, unique tracepoint IDs, `locals/stack`, capture limits and defensive copies.
2. RED: add strict Schema tests proving unknown `localVariables/projection/sampling` fields are rejected.
3. GREEN: add `JdwpTracepointSpec`, `JdwpCaptureSpec`, `JdwpCollectionPlan`, `JdwpCollectionRecord`, completion/stage enums and `JdwpCollectionManifest`.
4. GREEN: default stack-only capture; permit `locals=true` only with conservative P3 limits.
5. Build the external Collector from commit `1ef7d22`, compute full JAR SHA-256, record commit/version/license/JAR identity in the lock file.
6. Run `mvn -pl ada-contracts -am test` and parse every repository Schema.
7. Audit contract/API Javadoc, capability honesty, path portability and backward compatibility.
8. Commit: `feat: define bounded jdwp collection contracts`.

## Task 2: Compile source anchors into the locked Collector plan

**Files:**

- Create `debug-plan-engine/.../JdwpPlanRequest.java`
- Create `debug-plan-engine/.../JdwpTracepointRequest.java`
- Create `debug-plan-engine/.../JdwpPlanCompiler.java`
- Create `debug-plan-engine/.../CollectorDebugPlan.java` as package-private/private adapter DTO
- Create `debug-plan-engine/.../CollectorDebugPlanWriter.java`
- Add unit and compatibility fixtures/tests

1. RED: unknown method key, duplicate point, line outside method, changed file Hash, changed catalog fingerprint and excessive estimated cost.
2. RED: deterministic ordering/JSON and exact mapping to Collector fields.
3. RED: strict rejection of unsupported capability requests with `JDWP_UNSUPPORTED_CAPABILITY`.
4. GREEN: resolve every request from the current `MethodCatalog`; never trust caller-supplied class/method/source identity.
5. GREEN: re-hash each source file and bind `className/methodName/line/sourceSha256` into the Agent Plan.
6. GREEN: compile host `127.0.0.1`, runtime port, `resumeOnAttach=true`, timeout/events and supported capture limits into Collector JSON.
7. Validate generated JSON by the locked external Collector `DebugPlan.validate()` in a conditional compatibility test.
8. Run `mvn -pl debug-plan-engine -am test` and `mvn test`.
9. Audit deterministic output, no external DTO leakage and no silent fallback.
10. Commit: `feat: compile locked jdwp collector plans`.

## Task 3: Add a reusable asynchronous managed-process boundary

**Files:**

- Create `debug-harness/.../ManagedProcessRunner.java`
- Create `debug-harness/.../ManagedProcess.java`
- Create focused immutable start/completion specifications if required
- Reuse/refactor `BoundedOutputCapture`, `ProcessSupervisor`, `ProcessLimits`
- Add fault-injection tests

1. RED: process starts without blocking, stdout/stderr pump concurrently and remain byte bounded.
2. RED: wait for a bounded output marker while also noticing early process exit.
3. RED: timeout, interruption, log failure and explicit close terminate the complete descendant tree.
4. RED: close/terminate are idempotent and return truthful termination facts.
5. GREEN: implement the smallest process lifecycle abstraction; keep JDWP semantics out of `debug-harness`.
6. Refactor existing runners only where necessary and retain all P1/P2 behavior.
7. Run `mvn -pl debug-harness -am test` and CodePath regression tests.
8. Audit ownership, thread shutdown, process survivors, Windows behavior and exception causes.
9. Commit: `feat: supervise coordinated external processes`.

## Task 4: Coordinate the suspended target and JDWP Collector

**Files:**

- Modify `jdwp-collector-adapter/pom.xml`
- Create `LoopbackPortAllocator.java`
- Create `JdwpExecutionRequest.java` and `JdwpExecutionResult.java`
- Create `JdwpTargetCommandFactory.java`
- Create `JdwpCollectorCommandFactory.java`
- Create `JdwpCollectionCoordinator.java`
- Add unit/integration fixtures and tests

1. RED: only loopback port allocation and exact non-shell argv.
2. RED: target starts first; Collector starts only after bounded JDWP listening output appears.
3. RED: target compilation/start failure, readiness timeout, Collector start/attach failure, Collector nonzero exit, target timeout and both-success paths.
4. RED: Collector failure after target suspension always cleans the Maven/Surefire tree.
5. GREEN: application flow allocates loopback port before writing the Collector Plan; pass that exact port into the execution request, and inject clock/process runner and tool paths for deterministic tests.
6. GREEN: archive separate target and Collector logs; normalize external output paths only in the Agent Manifest layer.
7. Add a conditional real Collector smoke against a minimal fixture, then Wafer Demo one-point stack-only smoke.
8. Run `mvn -pl jdwp-collector-adapter -am test`.
9. Audit resume/cleanup safety, port race behavior, bounded logs and locked JAR SHA verification.
10. Commit: `feat: coordinate jdwp target and collector`.

## Task 5: Archive and expose the JDWP application flow

**Files:**

- Modify `case-management/.../CaseArchiveRepository.java` and layout/tests
- Create `ada-core/.../JdwpCollectionApplicationService.java` or extract shared dynamic-collection helpers before adding the JDWP path
- Modify `ada-core/.../ControlPlaneServices.java`
- Modify CLI command parsing/execution for `plan jdwp create` and `collection jdwp execute`
- Add integration tests and ToolResponse golden fixtures

1. RED: create-new Plan and collection request under the owning `analysisId`; reject cross-case/context/analysis identity.
2. RED: successful collection exposes plan, Raw, external/Agent manifests, four logs, Gantt and Baseline Artifact references.
3. RED: business exception, assertion failure, attach failure, source drift, changed Gantt and Agent persistence failure.
4. GREEN: archive request before side effects, then validate source, execute, save failure/success Manifest and perform post-run source/Baseline checks.
5. GREEN: reuse the existing normalized Gantt SHA-256 comparison; do not introduce JDWP-specific business fields.
6. GREEN: return bounded ToolResponse 2.0 summaries; Raw contents remain Artifact references.
7. Run affected modules and `mvn test`.
8. Audit append-only semantics, evidence gate truthfulness, failure cause preservation and sensitive path handling.
9. Commit: `feat: execute archived jdwp collections`.

## Task 6: P3 release audit and real smoke

**Files:** design completion record, architecture/tool baseline, README/CLI examples, OpenCode Skill guidance, audit notes.

1. Rebuild/verify the locked Collector JAR and full SHA-256.
2. Run contract/Schema tests and generated-plan compatibility validation.
3. Run the real Wafer baseline without collection, then the same UT with one JDWP tracepoint.
4. Verify source Hash, normalized Gantt Hash or failure fingerprint, tracepoint hit, no truncation and no surviving process.
5. Run `mvn test`, relevant profile tests, `git diff --check` and inspect repository status.
6. Review all new public APIs, process cleanup branches, absolute-path leakage and evidence usability decisions; fix findings and rerun tests.
7. Update the detailed design implementation record and P1–P8 master status.
8. Commit audit/documentation separately if material: `docs: record P3 jdwp integration completion`.

## Plan self-review

- Scope matches P3 only; P4 normalizer/evidence and Collector P0 are explicitly excluded.
- The Plan models only features proven in Collector commit `1ef7d22`.
- Double-process coordination is isolated in harness/adapter layers, not embedded in contracts or business adapters.
- Every failure path has an archive, structured code, cause and cleanup assertion.
- TDD, per-module audit, real smoke and root regression gates are explicit.
- The user-visible workflow remains flexible: a model may reuse prior evidence or execute zero, one or multiple new JDWP plans within the same Case.
