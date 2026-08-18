# Local OpenCode Demo Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the existing `hellomvn` UT debuggable from OpenCode through archived Run, CodePath, JDWP and Evidence facts.

**Architecture:** Keep OpenCode as a thin planner over the stable JSON CLI. Extend `ada-core` only to compose existing deterministic modules and extend Case read/write surfaces; do not add an autonomous workflow engine.

**Tech Stack:** Java 21, Maven, JUnit 5, Jackson, PowerShell, OpenCode 1.18.15, Node built-in test runner.

**Spec:** `docs/designs/2026-08-19-local-opencode-demo-vertical-slice-design.md`

## Global Constraints

- Do not modify the target algorithm source, UT or POM.
- Every behavior change follows RED → GREEN → refactor → affected tests → audit.
- Preserve plan-before-collection and append-only Case artifacts.
- Remove only the JDWP Collector JAR fingerprint; preserve evidence and result hashes.
- Do not add MCP, another client, a workflow state machine, business Gantt diff or a generic adapter.
- Commit each independently verified task; do not push unless requested.

---

### Task 1: Remove the JDWP Collector JAR fingerprint

**Files:** JDWP configuration/request/coordinator/manifest contracts, Schema, CLI wiring, tests and P3 documentation.

**Produces:** `JdwpToolConfiguration(Path collectorJar, String version)` and a manifest without `toolSha256`.

- [x] Add failing tests that construct a readable Collector JAR without a supplied hash and assert missing JAR rejection.
- [x] Run `mvn -pl jdwp-collector-adapter,ada-core,algorithm-debug-cli -am test` and confirm compilation/test failure is caused by the old hash-required API.
- [x] Remove hash fields, verification code and `JDWP_TOOL_HASH_MISMATCH`; advance the current JDWP manifest Schema without a compatibility layer.
- [x] Re-run affected tests, JSON Schema tests and a configured JDWP smoke.
- [x] Audit that Gantt, Plan, Raw and Artifact hashes remain intact; commit.

### Task 2: Compose deterministic collection post-processing

**Files:** `ada-core/pom.xml`, collection application services, a focused post-processing service, archive repository/layout, integration tests.

**Produces:** after each successful or partial collection, archived normalization, validation, Evidence Bundle and sufficiency documents plus references in the CLI result.

- [x] Add failing Core/integration tests for CodePath and JDWP collection-to-evidence output.
- [x] Verify RED with `mvn -pl ada-core,integration-tests -am test`.
- [x] Add only the existing normalizer/validator/evidence dependencies and implement one post-processing service shared by both collectors.
- [x] Preserve Raw/Manifest when post-processing fails and expose a separate structured Agent failure.
- [x] Cover success, zero hit, truncation, target failure and baseline change; run affected tests and commit.

### Task 3: Complete the bounded multi-turn Case view

**Files:** `ada-contracts`, Case layout/repository/digest reader, Core Case service, Schemas and tests.

**Produces:** immutable `AnalysisResult` and a bounded Case Digest containing recent Collection/Evidence/result summaries.

- [x] Add failing contract/repository tests for append-only Analysis Result and recent evidence visibility.
- [x] Verify RED with `mvn -pl ada-contracts,case-management,ada-core -am test`.
- [x] Implement Analysis Result persistence without model chain-of-thought and extend Digest with maximum 20 recent entries.
- [x] Preserve explicit Context rules and tolerate damaged child documents with warnings.
- [x] Run contract, repository and Core tests; audit write-once behavior and commit.

### Task 4: Add bounded Artifact reading and Analysis completion CLI commands

**Files:** Case artifact access, Core services, CLI command/parser/executor/README and tests.

**Produces:** `artifact read` and `analysis complete` ToolResponse commands.

- [x] Add failing tests for valid excerpt, truncation, unknown artifact, traversal and symlink escape.
- [x] Verify RED with `mvn -pl case-management,ada-core,algorithm-debug-cli -am test`.
- [x] Implement registered-Artifact-only reads and append-only Analysis completion.
- [x] Add CLI parsing and stable JSON responses; run affected tests and commit.

### Task 5: Provide a local ADA launcher

**Files:** `bin/ada.cmd`, packaging/config documentation, `.gitignore`, launcher verification tests or scripted checks.

**Produces:** a command that starts the shaded CLI with configured CodePath and JDWP JAR paths without manual classpath construction.

- [x] Define a failing process-level check showing the launcher is missing or Doctor cannot see configured tools.
- [x] Package the shaded CLI and CodePath launcher, then implement the minimal Windows launcher and local ignored Workspace/config.
- [x] Verify `ada doctor` with Java, Maven, CodePath, JDWP and `hellomvn`; commit.

### Task 6: Align the OpenCode tools with the real CLI

**Files:** `integrations/opencode/tools`, `lib/ada-cli.mjs`, Node tests, Agent/command assets.

**Produces:** begin, inspect, run, static, CodePath plan/collect, JDWP plan/collect, artifact read and analysis complete tools.

- [x] Add failing Node tests for every actual argv mapping and temporary request-file cleanup.
- [x] Run `node --test integrations/opencode/test/*.test.mjs` and confirm RED.
- [x] Replace placeholder command names with real CLI calls; resolve the current project and external Workspace deterministically.
- [x] Keep stdout/stderr/time budgets and ToolResponse validation; run Node tests and commit.

### Task 7: Install and verify OpenCode 1.18.15 integration

**Files:** one PowerShell install/check script, OpenCode README and integration fixtures.

**Produces:** idempotent `install` and `check` that register repository-owned Skill, Agent, command and tools while preserving user config.

- [ ] Add a temporary-config test that fails because assets are not currently discovered.
- [ ] Implement backup-preserving install and non-mutating check; do not add upgrade/uninstall.
- [ ] Verify with `opencode debug config`, `opencode debug skill` and tool discovery under a temporary config first, then local install.
- [ ] Confirm `algorithm-debug` is visible and commit.

### Task 8: Run the real `hellomvn` acceptance and final audit

**Files:** integration/E2E tests and completion records only; do not change Demo production code or UT.

**Produces:** evidence that the existing reproduction UT supports Run → CodePath → JDWP → Evidence and same-Case follow-up.

- [ ] Run the target UT baseline through ADA and archive Gantt.
- [ ] Run exact CodePath plan/collection and verify baseline match plus usable Evidence.
- [ ] Run one bounded JDWP plan/collection and verify baseline match plus usable Evidence.
- [ ] Run a second Analysis that reuses history without a forced UT run.
- [ ] Run failure Fixtures, full `mvn clean test`, Node tests, `git diff --check`, architecture scans and document the actual results.
- [ ] Use `superpowers:verification-before-completion`, audit/fix, then commit the final completion record.
