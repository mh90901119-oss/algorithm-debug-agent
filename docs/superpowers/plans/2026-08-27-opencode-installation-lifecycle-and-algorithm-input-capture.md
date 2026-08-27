# OpenCode Installation Lifecycle and Algorithm Input Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans and execute tasks serially with review gates.

**Goal:** Remove environment-bound terminology and Demo path assumptions, deliver safe OpenCode uninstall/reinstall, and archive one explicit target-UT input JSON at the start of every Analysis.

**Architecture:** PowerShell owns portable installation lifecycle from repository configuration. Java owns deterministic Javac-AST input discovery, bounded copying, Artifact registration, multi-turn SHA comparison, and dynamic-execution gates. OpenCode Skill/Tools expose the sequence without performing filesystem copies in the LLM.

**Tech Stack:** PowerShell, Java 21, Maven, Javac Tree API, Node test runner, OpenCode Custom Tools.

**Spec:** `docs/designs/2026-08-27-opencode-installation-lifecycle-and-algorithm-input-capture-design.md`

## Global Constraints

- Execute tasks in order; audit and pass each task's tests before starting the next.
- Do not add target-project path command parameters or configuration fields.
- Do not modify target algorithm production source.
- Do not delete Workspace, history, other OpenCode extensions, Provider, model, or shared runtime dependencies.
- Keep deterministic copy/validation in code and semantic interpretation in the LLM.
- Do not use Git unless the user separately requests it.

---

### Task 1: Neutral terminology and file names

**Files:** Rename the three `target-environment-*` documents; update AGENTS, README, configuration, architecture, design, decision, audit, test, and OpenCode integration references returned by the repository scan.

- [ ] Record the current `目标环境|target-environment|target-environment` content and path-name scan as the RED baseline.
- [ ] Rename the three files to `target-algorithm-*` names and update every link.
- [ ] Rewrite each occurrence contextually as target algorithm, target environment, restricted environment, configured Maven mirror, or sensitive local path.
- [ ] Replace `com.target-environment` test/example data with `org.example.targetalgorithm` and neutral sensitive-path fixtures.
- [ ] Run the content/path-name scans and require zero owned-source matches.
- [ ] Run `mvn -pl algorithm-debug-cli -am test` and audit that behavior did not change.

### Task 2: Portable installer and manual

**Files:** Modify `scripts/verify-ada-launcher.ps1`, audit installer/build/runtime scripts, update installer tests and renamed installation manual.

- [ ] Add tests requiring no `hellomvn` or target-module absolute path in installer/verification assets.
- [ ] Change launcher verification to use the current working directory and require its `pom.xml`.
- [ ] Keep repository discovery at `$PSScriptRoot` and all editable machine paths in `config/agent-settings.json`.
- [ ] Repair malformed installer comments and ensure validation completes before OpenCode writes.
- [ ] Rewrite the installation manual in actual execution order and remove branch-specific/local-topology assumptions.
- [ ] Run Node installation tests and `scripts/verify-opencode-installer.ps1`.
- [ ] Build, Install, Check, and run launcher verification from the local target algorithm Demo working directory.
- [ ] Audit real OpenCode Agent, Skill, Command, and twelve Custom Tool discoveries.

### Task 3: Safe uninstall and reinstall

**Files:** Create `scripts/uninstall-opencode.ps1`, create `docs/testing/opencode-uninstallation.md`, extend `scripts/install-opencode.ps1` and installer verification tests.

- [ ] Add failing lifecycle tests for install manifest, uninstall, idempotent uninstall, unrelated sentinel preservation, conflict zero-delete, and reinstall.
- [ ] Generate `.algorithm-debug-agent/install-manifest.json` after successful Install with managed relative paths and SHA-256 values.
- [ ] Make Check verify source bytes, generated installation module, ownership manifest, and real OpenCode discovery.
- [ ] Implement uninstall preflight; fail before deletion if any managed file changed.
- [ ] Delete only verified managed files, manifest, and empty Agent-specific directories.
- [ ] Preserve Workspace, DFX, Eval, OpenCode dependencies, other extensions, and legacy backup files.
- [ ] Document normal uninstall, reinstall, conflict handling, retained data, and first migration from an installation without a manifest.
- [ ] Run isolated lifecycle tests, then uninstall the current configuration, verify absence, reinstall, and Check.

### Task 4: Algorithm input discovery contracts and AST locator

**Files:** Modify `ada-contracts`, `static-analysis`, schemas, and their unit tests.

- [ ] Add RED tests for first-level direct `String` literals ending in `input.json`, absolute/relative paths, nested exclusions, unsupported expressions, zero and multiple unique paths.
- [ ] Add versioned immutable input discovery/snapshot/comparison contracts and JSON Schema examples.
- [ ] Implement `TargetTestInputLocator` using Javac AST and only direct target-method body statements.
- [ ] Resolve relative paths from registered module root, normalize unique paths, and return stable source anchors without source SHA.
- [ ] Run `mvn -pl static-analysis,ada-contracts -am test`.

### Task 5: Input archive, comparison, and execution gates

**Files:** Modify `case-management`, `ada-core`, `algorithm-debug-cli`, tests, and Workspace audit rules.

- [ ] Add RED repository tests for Analysis input paths, append-only documents, atomic 256 MiB-bounded copy, Artifact registration, and previous-input lookup.
- [ ] Add `AlgorithmInputCaptureService` and `AnalysisInputGate` with structured domain codes.
- [ ] Archive `input-analysis.json` and `algorithm-input.json` under the current Analysis; register `ALGORITHM_INPUT`.
- [ ] Compare the current Artifact SHA with the latest previous successful input in the same Case.
- [ ] Add CLI `input capture --workspace --project-id --case-id --analysis-id` using existing internal resolved-path transport.
- [ ] Gate run_test, CodePath, and JDWP before external-process launch.
- [ ] Extend Case audit expectations for valid and failed input-capture states without creating empty directories.
- [ ] Run affected module tests through `algorithm-debug-cli` and integration tests.

### Task 6: OpenCode workflow, Skill, Eval, and documentation

**Files:** Modify OpenCode Tool/Runtime, Agent prompt, Skill, Eval suites, capability and workflow documentation.

- [ ] Add RED Node tests for `algorithm_input_capture`, ToolResponse validation, ordering, and stop behavior.
- [ ] Export the Custom Tool and map it to CLI `input capture`.
- [ ] Require `analysis_begin -> algorithm_input_capture -> artifact_read as needed -> run_test` in Skill and Agent instructions.
- [ ] Require immediate dynamic-stop and `MISSING_EVIDENCE` completion for zero/multiple/missing/unsupported input.
- [ ] Require rerun of target UT when comparison is CHANGED; document that UNCHANGED proves only input bytes.
- [ ] Add Eval cases for relative input success, absolute input success, multiple inputs, missing input file, and changed input in a continued Case.
- [ ] Update Workspace file inventory and current capabilities.
- [ ] Run all OpenCode and Eval Node tests.

### Task 7: Full verification and audit

- [ ] Run `mvn test`.
- [ ] Run `mvn -Pcodepath-launcher -pl tools/code-path-tracer-junit-launcher -am test`.
- [ ] Run `node --test agent-evals/test/*.test.mjs integrations/opencode/test/*.test.mjs`.
- [ ] Run installer, launcher, and JDWP loopback verification scripts.
- [ ] Run a real local OpenCode target-UT success flow and audit every expected Case/Analysis/Run/Artifact/log file.
- [ ] Run a real multiple-input flow and prove no Maven/JUnit/CodePath/JDWP process starts.
- [ ] Execute uninstall, verify managed capability absence and retained Workspace, reinstall, and real OpenCode Check.
- [ ] Scan owned source and paths for neutral terminology, Demo path binding, temporary files, empty generated directories, and stale documentation links.
- [ ] Record actual results and residual limits in the design completion section and final audit document.

