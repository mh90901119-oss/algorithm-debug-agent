# Core Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove unused runtime surface and operational hash/version gates while preserving one deterministic Artifact integrity implementation, Plan SHA, normalized result SHA, Provenance, and Baseline evidence semantics.

**Architecture:** `case-management` owns a neutral `ArtifactIntegrityChecker`; Case reads and `trace-validator` reuse it without changing public Tool or Finding contracts. Empty modules are removed, Agent eval expectations become a manual checklist, and external tool compatibility relies on real capability checks rather than exact binary/version identity.

**Tech Stack:** Java 21, Maven, JUnit 5, PowerShell, OpenCode TypeScript/Node tests.

**Spec:** `docs/designs/2026-08-19-core-simplification-design.md`

## Global Constraints

- Preserve append-only Case/Run/Analysis artifacts and all evidence provenance.
- Preserve Plan SHA, normalized JSON result SHA, SourceAnchor SHA, Baseline comparison, timeouts and process cleanup.
- Do not add a new module, framework, hash strategy, evaluator, reporter, or Gantt diff.
- Keep OpenCode Tool and CLI contracts stable.

---

### Task 1: Unified Artifact integrity

**Files:**
- Create: `case-management/src/main/java/org/example/algorithmdebug/casecore/ArtifactIntegrityChecker.java`
- Create: `case-management/src/test/java/org/example/algorithmdebug/casecore/ArtifactIntegrityCheckerTest.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/CaseArtifactAccess.java`
- Modify: `case-management/src/main/java/org/example/algorithmdebug/casecore/RegisteredArtifactReader.java`
- Modify: `trace-validator/pom.xml`
- Modify: `trace-validator/src/main/java/org/example/algorithmdebug/validator/ArtifactIntegrityVerifier.java`

- [ ] Add failing Checker and tampered-reader tests.
- [ ] Run focused tests and confirm failures are caused by the missing API.
- [ ] Implement the neutral Checker and migrate Case access.
- [ ] Delegate Validator integrity and Plan SHA file hashing to the Checker.
- [ ] Run case-management and trace-validator tests.

### Task 2: Remove empty modules and retain acceptance cases

**Files:**
- Modify: `pom.xml`
- Create: `docs/testing/agent-acceptance-cases.md`
- Delete: `agent-evaluation/`, `gantt-analysis/`, `knowledge-engine/`, `explanation-reporter/`

- [ ] Convert the five P3 Golden cases to a manual acceptance checklist.
- [ ] Remove the four modules from the reactor and filesystem.
- [ ] Confirm no production module depends on them.

### Task 3: Remove CodePath binary SHA gate

**Files:**
- Modify: `algorithm-debug-cli/src/main/java/org/example/algorithmdebug/cli/AdaMain.java`
- Modify: `algorithm-debug-cli/src/test/java/org/example/algorithmdebug/cli/AdaMainTest.java`
- Modify: `config/toolchain-lock.json`
- Modify: relevant usage documentation.

- [ ] Change the Doctor expectation first so a configured Launcher needs no SHA environment variable.
- [ ] Remove `ADA_CODEPATH_LAUNCHER_SHA256` and fixed lock configuration.
- [ ] Keep MethodPath v2 `toolSha256` nullable for compatibility.
- [ ] Run CLI tests.

### Task 4: Relax exact OpenCode version gate

**Files:**
- Modify: `scripts/install-opencode.ps1`
- Modify: `integrations/opencode/README.md`

- [ ] Replace exact-version failure with verified/unverified messaging.
- [ ] Keep Skill, Agent, Command and ten-Tool discovery as the compatibility gate.
- [ ] Run Node tests and real installer Check.

### Task 5: Current capability documentation

**Files:**
- Create: `docs/current-capabilities.md`
- Modify: `README.md`
- Modify: `algorithm-debug-cli/README.md`
- Modify: `docs/architecture/README.md`
- Modify: owning design completion records.

- [ ] Document completed flow, configuration and explicit non-goals.
- [ ] Explain Artifact, Plan and normalized result SHA in one table.
- [ ] Mark historical status documents as non-authoritative where needed.

### Task 6: Verification and completion record

- [ ] Run affected module tests.
- [ ] Run root `mvn test`.
- [ ] Run OpenCode Node tests and schema validation.
- [ ] Run real OpenCode `Install/Check` and a bounded smoke if available.
- [ ] Fill the design implementation completion record with exact commands and limitations.
